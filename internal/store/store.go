// Package store owns the complete SQLite persistence boundary.
package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/url"
	"os"
	pathpkg "path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/discovery"
	querymodel "github.com/coadan/yggdrasil/query/model"
	"github.com/ncruces/go-sqlite3"
	"github.com/ncruces/go-sqlite3/driver"
	"github.com/ncruces/go-sqlite3/ext/fts5"
	"github.com/ncruces/go-sqlite3/ext/vec1"
)

const schemaVersion = "5"
const indexFreshnessKey = "index_freshness_token"

// SearchLayout names the derived search tables a writer builds when it opens
// an index. Freshness tokens include it, so a snapshot written before a layout
// change reads as stale until a refresh has built the current tables.
const SearchLayout = "search-layout:core-fts-2"

type Store struct {
	db     *sql.DB
	readTx *sql.Tx
	path   string
}

type FileState struct {
	Path                  string
	Size                  int64
	MTimeNS               int64
	ContentHash           string
	ExtractionFingerprint string
}

type FileUpdate struct {
	File                  discovery.File
	ContentHash           string
	ExtractionFingerprint string
	Records               []contracts.SearchRecord
}

type FileRefresh struct {
	Path    string
	Size    int64
	MTimeNS int64
}

type Diagnostic struct {
	Path    string
	Stage   string
	Message string
}

type Counts struct {
	Files       int `json:"files"`
	Records     int `json:"records"`
	GraphEdges  int `json:"graphEdges"`
	Diagnostics int `json:"diagnostics"`
}

type Record = querymodel.Candidate

type EmbeddingInput struct {
	ID        int64
	InputHash string
	Text      string
}

type EmbeddingPriority struct {
	// Paths are ordered from highest to lowest expected retrieval impact.
	Paths []string
	Scope string
	// ChangedPaths were re-extracted in the current index generation. They are
	// lower priority than explicit demand and scope, but precede cold records.
	ChangedPaths []string
}

type EmbeddingValue struct {
	ID        int64
	InputHash string
	Vector    []float32
}

type EmbeddingState = querymodel.EmbeddingState

type Run struct {
	ID           string         `json:"id"`
	StartedAtMS  int64          `json:"startedAtMs"`
	FinishedAtMS *int64         `json:"finishedAtMs,omitempty"`
	Status       string         `json:"status"`
	Summary      map[string]any `json:"summary,omitempty"`
}

func Open(ctx context.Context, path, root, rootID string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, fmt.Errorf("create state directory: %w", err)
	}
	db, err := openDatabase(path)
	if err != nil {
		return nil, fmt.Errorf("open sqlite: %w", err)
	}
	db.SetMaxOpenConns(1)
	value := &Store{db: db, path: path}
	if err := value.initialize(ctx, root, rootID); err != nil {
		db.Close()
		return nil, err
	}
	return value, nil
}

// OpenReadOnly opens an existing index without schema initialization or a busy
// wait. Reads share one transaction until Close or ctx cancellation. The first
// read pins its generation; later statements cannot see concurrent commits.
func OpenReadOnly(ctx context.Context, path string) (*Store, error) {
	uri := (&url.URL{Scheme: "file", Path: path}).String()
	db, err := driver.Open(
		uri+"?mode=ro&_pragma=query_only(1)&_pragma=busy_timeout(0)",
		func(conn *sqlite3.Conn) error {
			if err := fts5.Register(conn); err != nil {
				return err
			}
			return vec1.Register(conn)
		},
	)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if err := db.PingContext(ctx); err != nil {
		db.Close()
		return nil, err
	}
	tx, err := db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		db.Close()
		return nil, err
	}
	return &Store{db: db, readTx: tx, path: path}, nil
}

// CloneDatabase creates a transactionally consistent snapshot and gives the
// copy a new repository identity. Records and vectors remain intact.
func CloneDatabase(
	ctx context.Context,
	source, sourceRoot, destination, root, rootID string,
) (err error) {
	if err := os.MkdirAll(filepath.Dir(destination), 0o755); err != nil {
		return fmt.Errorf("create seed state directory: %w", err)
	}
	sourceDB, err := openDatabase(source)
	if err != nil {
		return fmt.Errorf("open seed index: %w", err)
	}
	defer sourceDB.Close()
	connection, err := sourceDB.Conn(ctx)
	if err != nil {
		return fmt.Errorf("open seed connection: %w", err)
	}
	defer connection.Close()
	destinationURI := (&url.URL{Scheme: "file", Path: destination}).String()
	defer func() {
		if err != nil {
			for _, path := range []string{destination, destination + "-wal", destination + "-shm"} {
				_ = os.Remove(path)
			}
		}
	}()
	if err := connection.Raw(func(raw any) error {
		return raw.(driver.Conn).Raw().Backup("main", destinationURI)
	}); err != nil {
		return fmt.Errorf("snapshot seed index: %w", err)
	}
	copyDB, err := openDatabase(destination)
	if err != nil {
		return fmt.Errorf("open seeded index: %w", err)
	}
	defer copyDB.Close()
	tx, err := copyDB.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin seed retarget: %w", err)
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(
		ctx, `UPDATE repository SET root_id=?,root=? WHERE id=1`, rootID, root,
	); err != nil {
		return fmt.Errorf("retarget seeded index: %w", err)
	}
	for _, table := range []string{"diagnostics", "index_runs"} {
		if _, err := tx.ExecContext(ctx, `DELETE FROM `+table); err != nil {
			return fmt.Errorf("clear seeded %s: %w", table, err)
		}
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO meta(key,value) VALUES('worktree_seed_source',?)
		ON CONFLICT(key) DO UPDATE SET value=excluded.value`, sourceRoot); err != nil {
		return fmt.Errorf("mark seed reconciliation: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit seed retarget: %w", err)
	}
	return nil
}

func (s *Store) PendingSeed(ctx context.Context) (string, error) {
	var source string
	err := s.queryRowContext(
		ctx, `SELECT value FROM meta WHERE key='worktree_seed_source'`,
	).Scan(&source)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return source, err
}

func (s *Store) CompleteSeed(ctx context.Context) error {
	_, err := s.execContext(ctx, `DELETE FROM meta WHERE key='worktree_seed_source'`)
	return err
}

func (s *Store) IndexFreshnessToken(ctx context.Context) (string, error) {
	var token string
	err := s.queryRowContext(
		ctx, `SELECT value FROM meta WHERE key=?`, indexFreshnessKey,
	).Scan(&token)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return token, err
}

func (s *Store) SetIndexFreshnessToken(ctx context.Context, token string) error {
	if token == "" {
		_, err := s.execContext(ctx, `DELETE FROM meta WHERE key=?`, indexFreshnessKey)
		return err
	}
	_, err := s.execContext(ctx, `
		INSERT INTO meta(key,value) VALUES(?,?)
		ON CONFLICT(key) DO UPDATE SET value=excluded.value`,
		indexFreshnessKey, token)
	return err
}

func openDatabase(path string) (*sql.DB, error) {
	uri := (&url.URL{Scheme: "file", Path: path}).String()
	db, err := driver.Open(uri+"?_pragma=busy_timeout(1000)", func(conn *sqlite3.Conn) error {
		if err := fts5.Register(conn); err != nil {
			return err
		}
		return vec1.Register(conn)
	})
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	return db, nil
}

func (s *Store) Close() error {
	var err error
	if s.readTx != nil {
		err = s.readTx.Rollback()
		if errors.Is(err, sql.ErrTxDone) {
			err = nil
		}
	}
	return errors.Join(err, s.db.Close())
}

func (s *Store) queryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error) {
	if s.readTx != nil {
		return s.readTx.QueryContext(ctx, query, args...)
	}
	return s.db.QueryContext(ctx, query, args...)
}

func (s *Store) queryRowContext(ctx context.Context, query string, args ...any) *sql.Row {
	if s.readTx != nil {
		return s.readTx.QueryRowContext(ctx, query, args...)
	}
	return s.db.QueryRowContext(ctx, query, args...)
}

func (s *Store) execContext(ctx context.Context, query string, args ...any) (sql.Result, error) {
	if s.readTx != nil {
		return nil, sqlite3.READONLY
	}
	return s.db.ExecContext(ctx, query, args...)
}

func (s *Store) beginTx(ctx context.Context, opts *sql.TxOptions) (*sql.Tx, error) {
	if s.readTx != nil {
		return nil, sqlite3.READONLY
	}
	return s.db.BeginTx(ctx, opts)
}

func (s *Store) Path() string {
	return s.path
}

func (s *Store) initialize(ctx context.Context, root, rootID string) error {
	statements := []string{
		`PRAGMA journal_mode=WAL`,
		`PRAGMA synchronous=NORMAL`,
		`PRAGMA foreign_keys=ON`,
		`CREATE TABLE IF NOT EXISTS meta (
			key TEXT PRIMARY KEY,
			value TEXT NOT NULL
		)`,
		`CREATE TABLE IF NOT EXISTS repository (
			id INTEGER PRIMARY KEY CHECK (id = 1),
			root_id TEXT NOT NULL,
			root TEXT NOT NULL
		)`,
		`CREATE TABLE IF NOT EXISTS index_runs (
			id TEXT PRIMARY KEY,
			started_at_ms INTEGER NOT NULL,
			finished_at_ms INTEGER,
			status TEXT NOT NULL,
			summary_json TEXT
		)`,
		`CREATE TABLE IF NOT EXISTS files (
			id INTEGER PRIMARY KEY,
			path TEXT NOT NULL UNIQUE,
			kind TEXT NOT NULL,
			size INTEGER NOT NULL,
			mtime_ns INTEGER NOT NULL,
			content_hash TEXT NOT NULL,
			extraction_fingerprint TEXT NOT NULL,
			indexed_at_ms INTEGER NOT NULL,
			run_id TEXT NOT NULL
		)`,
		`CREATE TABLE IF NOT EXISTS records (
			id INTEGER PRIMARY KEY,
			record_key TEXT NOT NULL UNIQUE,
			file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
			path TEXT NOT NULL,
			start_line INTEGER NOT NULL,
			end_line INTEGER NOT NULL,
			kind TEXT NOT NULL,
			title TEXT NOT NULL,
			text TEXT NOT NULL,
			metadata_json TEXT NOT NULL,
			source TEXT NOT NULL
			,input_hash TEXT NOT NULL
		)`,
		`CREATE INDEX IF NOT EXISTS records_path_file_idx ON records(path) WHERE kind='file'`,
		`CREATE INDEX IF NOT EXISTS records_input_hash_idx ON records(input_hash)`,
		`CREATE TABLE IF NOT EXISTS embedding_lane (
			id INTEGER PRIMARY KEY CHECK (id = 1),
			fingerprint TEXT NOT NULL,
			model TEXT NOT NULL,
			dimensions INTEGER NOT NULL,
			updated_at_ms INTEGER NOT NULL
		)`,
		`CREATE TABLE IF NOT EXISTS embedding_records (
			record_id INTEGER PRIMARY KEY REFERENCES records(id) ON DELETE CASCADE,
			input_hash TEXT NOT NULL,
			fingerprint TEXT NOT NULL
		)`,
		`CREATE VIRTUAL TABLE IF NOT EXISTS record_vectors USING vec1(vector)`,
		`CREATE VIRTUAL TABLE IF NOT EXISTS record_fts USING fts5(
			path, title, text,
			content='records',
			content_rowid='id',
			tokenize='unicode61'
		)`,
		`CREATE VIRTUAL TABLE IF NOT EXISTS stemmed_fts USING fts5(
			path, title, text,
			content='records',
			content_rowid='id',
			tokenize='porter unicode61'
		)`,
		`CREATE VIRTUAL TABLE IF NOT EXISTS extractor_fts USING fts5(
			path, title, text,
			content='records',
			content_rowid='id',
			tokenize='unicode61'
		)`,
		`CREATE TRIGGER IF NOT EXISTS records_embedding_ad AFTER DELETE ON records BEGIN
			DELETE FROM embedding_records WHERE record_id=old.id;
		END`,
		`CREATE TRIGGER IF NOT EXISTS records_extractor_ai AFTER INSERT ON records
		WHEN new.source LIKE 'plugin:%'
			AND coalesce(json_extract(new.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO extractor_fts(rowid, path, title, text)
			VALUES (new.id, new.path, new.title, new.text);
		END`,
		`CREATE TRIGGER IF NOT EXISTS records_extractor_ad AFTER DELETE ON records
		WHEN old.source LIKE 'plugin:%'
			AND coalesce(json_extract(old.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO extractor_fts(extractor_fts, rowid, path, title, text)
			VALUES ('delete', old.id, old.path, old.title, old.text);
		END`,
		`CREATE TRIGGER IF NOT EXISTS records_extractor_au_delete AFTER UPDATE ON records
		WHEN old.source LIKE 'plugin:%'
			AND coalesce(json_extract(old.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO extractor_fts(extractor_fts, rowid, path, title, text)
			VALUES ('delete', old.id, old.path, old.title, old.text);
		END`,
		`CREATE TRIGGER IF NOT EXISTS records_extractor_au_insert AFTER UPDATE ON records
		WHEN new.source LIKE 'plugin:%'
			AND coalesce(json_extract(new.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO extractor_fts(rowid, path, title, text)
			VALUES (new.id, new.path, new.title, new.text);
		END`,
		`CREATE TABLE IF NOT EXISTS graph_edges (
			id INTEGER PRIMARY KEY,
			source_path TEXT NOT NULL,
			relation TEXT NOT NULL,
			target TEXT NOT NULL,
			target_path TEXT,
			record_id INTEGER NOT NULL REFERENCES records(id) ON DELETE CASCADE,
			UNIQUE(record_id,relation,target)
		)`,
		`CREATE INDEX IF NOT EXISTS graph_edges_source_idx ON graph_edges(source_path)`,
		`CREATE INDEX IF NOT EXISTS graph_edges_target_path_idx ON graph_edges(target_path)`,
		`CREATE TABLE IF NOT EXISTS diagnostics (
			id INTEGER PRIMARY KEY,
			run_id TEXT NOT NULL,
			path TEXT,
			stage TEXT NOT NULL,
			message TEXT NOT NULL,
			created_at_ms INTEGER NOT NULL
		)`,
	}
	for _, statement := range append(statements, coreFTSTriggerStatements()...) {
		if _, err := s.execContext(ctx, statement); err != nil {
			return fmt.Errorf("initialize sqlite: %w", err)
		}
	}
	var version string
	err := s.queryRowContext(ctx, `SELECT value FROM meta WHERE key='schema_version'`).Scan(&version)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		if _, err := s.execContext(ctx, `INSERT INTO meta(key,value) VALUES('schema_version',?)`, schemaVersion); err != nil {
			return err
		}
	case err != nil:
		return err
	case version == "2":
		if err := s.migrateV2ToV3(ctx); err != nil {
			return err
		}
		if err := s.migrateV3ToV4(ctx); err != nil {
			return err
		}
		if err := s.migrateV4ToV5(ctx); err != nil {
			return err
		}
	case version == "3":
		if err := s.migrateV3ToV4(ctx); err != nil {
			return err
		}
		if err := s.migrateV4ToV5(ctx); err != nil {
			return err
		}
	case version == "4":
		if err := s.migrateV4ToV5(ctx); err != nil {
			return err
		}
	case version != schemaVersion:
		return fmt.Errorf("index schema %s is incompatible with %s; run ygg index --full", version, schemaVersion)
	}
	if err := s.markLegacyExtractionFailures(ctx); err != nil {
		return fmt.Errorf("prepare extraction retries: %w", err)
	}
	var extractorFTSVersion string
	err = s.queryRowContext(
		ctx, `SELECT value FROM meta WHERE key='extractor_fts_version'`,
	).Scan(&extractorFTSVersion)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		if _, err := s.execContext(ctx, `
			INSERT INTO extractor_fts(rowid,path,title,text)
			SELECT id,path,title,text FROM records
			WHERE source LIKE 'plugin:%'
				AND coalesce(json_extract(metadata_json,'$.lexical'),1)<>0`); err != nil {
			return fmt.Errorf("build extractor index: %w", err)
		}
		if _, err := s.execContext(
			ctx, `INSERT INTO meta(key,value) VALUES('extractor_fts_version','2')`,
		); err != nil {
			return err
		}
	case err != nil:
		return err
	case extractorFTSVersion != "2":
		return fmt.Errorf("unsupported extractor index version %s", extractorFTSVersion)
	}
	var stemmedFTSVersion string
	err = s.queryRowContext(
		ctx, `SELECT value FROM meta WHERE key='stemmed_fts_version'`,
	).Scan(&stemmedFTSVersion)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		if _, err := s.execContext(ctx, `
			INSERT INTO stemmed_fts(rowid,path,title,text)
			SELECT id,path,title,text FROM records
			WHERE source NOT LIKE 'plugin:%'
				AND coalesce(json_extract(metadata_json,'$.lexical'),1)<>0`); err != nil {
			return fmt.Errorf("build stemmed index: %w", err)
		}
		if _, err := s.execContext(
			ctx, `INSERT INTO meta(key,value) VALUES('stemmed_fts_version','1')`,
		); err != nil {
			return err
		}
	case err != nil:
		return err
	case stemmedFTSVersion != "1":
		return fmt.Errorf("unsupported stemmed index version %s", stemmedFTSVersion)
	}
	var coreFTSScope string
	err = s.queryRowContext(ctx, `SELECT value FROM meta WHERE key='core_fts_scope'`).Scan(&coreFTSScope)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		if err := s.scopeCoreFTS(ctx); err != nil {
			return err
		}
	case err != nil:
		return err
	case coreFTSScope != "1":
		return fmt.Errorf("unsupported core lexical index scope %s", coreFTSScope)
	}
	var storedID, storedRoot string
	err = s.queryRowContext(ctx, `SELECT root_id, root FROM repository WHERE id=1`).Scan(&storedID, &storedRoot)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		_, err = s.execContext(ctx, `INSERT INTO repository(id,root_id,root) VALUES(1,?,?)`, rootID, root)
		return err
	case err != nil:
		return err
	case storedID != rootID || storedRoot != root:
		return fmt.Errorf("index belongs to repository %s at %s", storedID, storedRoot)
	default:
		return nil
	}
}

// coreFTSTriggerStatements maintain the core and stemmed lexical indexes.
// Extractor plugin records stay in extractor_fts only: their many short
// declaration and import records otherwise shift BM25 term statistics and
// length normalization against core text.
func coreFTSTriggerStatements() []string {
	return []string{
		`CREATE TRIGGER IF NOT EXISTS records_ai AFTER INSERT ON records
		WHEN new.source NOT LIKE 'plugin:%'
			AND coalesce(json_extract(new.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO record_fts(rowid, path, title, text)
			VALUES (new.id, new.path, new.title, new.text);
		END`,
		`CREATE TRIGGER IF NOT EXISTS records_ad AFTER DELETE ON records
		WHEN old.source NOT LIKE 'plugin:%'
			AND coalesce(json_extract(old.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO record_fts(record_fts, rowid, path, title, text)
			VALUES ('delete', old.id, old.path, old.title, old.text);
		END`,
		`CREATE TRIGGER IF NOT EXISTS records_au_delete AFTER UPDATE ON records
		WHEN old.source NOT LIKE 'plugin:%'
			AND coalesce(json_extract(old.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO record_fts(record_fts, rowid, path, title, text)
			VALUES ('delete', old.id, old.path, old.title, old.text);
		END`,
		`CREATE TRIGGER IF NOT EXISTS records_au_insert AFTER UPDATE ON records
		WHEN new.source NOT LIKE 'plugin:%'
			AND coalesce(json_extract(new.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO record_fts(rowid, path, title, text)
			VALUES (new.id, new.path, new.title, new.text);
		END`,
		`CREATE TRIGGER IF NOT EXISTS records_stemmed_ai AFTER INSERT ON records
		WHEN new.source NOT LIKE 'plugin:%'
			AND coalesce(json_extract(new.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO stemmed_fts(rowid, path, title, text)
			VALUES (new.id, new.path, new.title, new.text);
		END`,
		`CREATE TRIGGER IF NOT EXISTS records_stemmed_ad AFTER DELETE ON records
		WHEN old.source NOT LIKE 'plugin:%'
			AND coalesce(json_extract(old.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO stemmed_fts(stemmed_fts, rowid, path, title, text)
			VALUES ('delete', old.id, old.path, old.title, old.text);
		END`,
		`CREATE TRIGGER IF NOT EXISTS records_stemmed_au_delete AFTER UPDATE ON records
		WHEN old.source NOT LIKE 'plugin:%'
			AND coalesce(json_extract(old.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO stemmed_fts(stemmed_fts, rowid, path, title, text)
			VALUES ('delete', old.id, old.path, old.title, old.text);
		END`,
		`CREATE TRIGGER IF NOT EXISTS records_stemmed_au_insert AFTER UPDATE ON records
		WHEN new.source NOT LIKE 'plugin:%'
			AND coalesce(json_extract(new.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO stemmed_fts(rowid, path, title, text)
			VALUES (new.id, new.path, new.title, new.text);
		END`,
	}
}

// scopeCoreFTS recreates the core lexical triggers and rebuilds both core
// indexes from core records once for indexes written before the scope split.
func (s *Store) scopeCoreFTS(ctx context.Context) error {
	tx, err := s.beginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, name := range []string{
		"records_ai", "records_ad", "records_au_delete", "records_au_insert",
		"records_stemmed_ai", "records_stemmed_ad", "records_stemmed_au_delete", "records_stemmed_au_insert",
	} {
		if _, err := tx.ExecContext(ctx, `DROP TRIGGER IF EXISTS `+name); err != nil {
			return fmt.Errorf("drop lexical trigger %s: %w", name, err)
		}
	}
	statements := append(coreFTSTriggerStatements(),
		`INSERT INTO record_fts(record_fts) VALUES('delete-all')`,
		`INSERT INTO record_fts(rowid,path,title,text)
		 SELECT id,path,title,text FROM records
		 WHERE source NOT LIKE 'plugin:%'
			AND coalesce(json_extract(metadata_json,'$.lexical'),1)<>0`,
		`INSERT INTO stemmed_fts(stemmed_fts) VALUES('delete-all')`,
		`INSERT INTO stemmed_fts(rowid,path,title,text)
		 SELECT id,path,title,text FROM records
		 WHERE source NOT LIKE 'plugin:%'
			AND coalesce(json_extract(metadata_json,'$.lexical'),1)<>0`,
		`INSERT INTO meta(key,value) VALUES('core_fts_scope','1')`,
	)
	for _, statement := range statements {
		if _, err := tx.ExecContext(ctx, statement); err != nil {
			return fmt.Errorf("scope core lexical index: %w", err)
		}
	}
	return tx.Commit()
}

func (s *Store) migrateV3ToV4(ctx context.Context) error {
	if err := s.RebuildGraph(ctx); err != nil {
		return fmt.Errorf("build mechanical graph: %w", err)
	}
	_, err := s.execContext(ctx, `UPDATE meta SET value='4' WHERE key='schema_version'`)
	return err
}

func (s *Store) migrateV4ToV5(ctx context.Context) error {
	if _, err := s.execContext(ctx, `CREATE INDEX IF NOT EXISTS records_input_hash_idx ON records(input_hash)`); err != nil {
		return fmt.Errorf("index embedding input hashes: %w", err)
	}
	_, err := s.execContext(ctx, `UPDATE meta SET value='5' WHERE key='schema_version'`)
	return err
}

func (s *Store) migrateV2ToV3(ctx context.Context) error {
	tx, err := s.beginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, name := range []string{
		"records_ai", "records_ad", "records_au", "records_au_delete", "records_au_insert",
		"records_embedding_ad", "records_extractor_ai", "records_extractor_ad",
		"records_extractor_au_delete", "records_extractor_au_insert",
	} {
		if _, err := tx.ExecContext(ctx, `DROP TRIGGER IF EXISTS `+name); err != nil {
			return fmt.Errorf("drop search trigger %s: %w", name, err)
		}
	}
	for _, statement := range searchTriggerStatements() {
		if _, err := tx.ExecContext(ctx, statement); err != nil {
			return fmt.Errorf("create search trigger: %w", err)
		}
	}
	for _, statement := range []string{
		`INSERT INTO record_fts(record_fts) VALUES('delete-all')`,
		`INSERT INTO record_fts(rowid,path,title,text)
		 SELECT id,path,title,text FROM records
		 WHERE source NOT LIKE 'plugin:%'
			AND coalesce(json_extract(metadata_json,'$.lexical'),1)<>0`,
		`INSERT INTO extractor_fts(extractor_fts) VALUES('delete-all')`,
		`INSERT INTO extractor_fts(rowid,path,title,text)
		 SELECT id,path,title,text FROM records
		 WHERE source LIKE 'plugin:%'
			AND coalesce(json_extract(metadata_json,'$.lexical'),1)<>0`,
		`UPDATE meta SET value='2' WHERE key='extractor_fts_version'`,
		`UPDATE meta SET value='3' WHERE key='schema_version'`,
	} {
		if _, err := tx.ExecContext(ctx, statement); err != nil {
			return fmt.Errorf("migrate search index: %w", err)
		}
	}
	return tx.Commit()
}

func searchTriggerStatements() []string {
	return []string{
		`CREATE TRIGGER records_ai AFTER INSERT ON records
		WHEN new.source NOT LIKE 'plugin:%'
			AND coalesce(json_extract(new.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO record_fts(rowid,path,title,text) VALUES(new.id,new.path,new.title,new.text);
		END`,
		`CREATE TRIGGER records_ad AFTER DELETE ON records
		WHEN old.source NOT LIKE 'plugin:%'
			AND coalesce(json_extract(old.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO record_fts(record_fts,rowid,path,title,text)
			VALUES('delete',old.id,old.path,old.title,old.text);
		END`,
		`CREATE TRIGGER records_embedding_ad AFTER DELETE ON records BEGIN
			DELETE FROM embedding_records WHERE record_id=old.id;
		END`,
		`CREATE TRIGGER records_au_delete AFTER UPDATE ON records
		WHEN old.source NOT LIKE 'plugin:%'
			AND coalesce(json_extract(old.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO record_fts(record_fts,rowid,path,title,text)
			VALUES('delete',old.id,old.path,old.title,old.text);
		END`,
		`CREATE TRIGGER records_au_insert AFTER UPDATE ON records
		WHEN new.source NOT LIKE 'plugin:%'
			AND coalesce(json_extract(new.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO record_fts(rowid,path,title,text) VALUES(new.id,new.path,new.title,new.text);
		END`,
		`CREATE TRIGGER records_extractor_ai AFTER INSERT ON records
		WHEN new.source LIKE 'plugin:%'
			AND coalesce(json_extract(new.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO extractor_fts(rowid,path,title,text) VALUES(new.id,new.path,new.title,new.text);
		END`,
		`CREATE TRIGGER records_extractor_ad AFTER DELETE ON records
		WHEN old.source LIKE 'plugin:%'
			AND coalesce(json_extract(old.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO extractor_fts(extractor_fts,rowid,path,title,text)
			VALUES('delete',old.id,old.path,old.title,old.text);
		END`,
		`CREATE TRIGGER records_extractor_au_delete AFTER UPDATE ON records
		WHEN old.source LIKE 'plugin:%'
			AND coalesce(json_extract(old.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO extractor_fts(extractor_fts,rowid,path,title,text)
			VALUES('delete',old.id,old.path,old.title,old.text);
		END`,
		`CREATE TRIGGER records_extractor_au_insert AFTER UPDATE ON records
		WHEN new.source LIKE 'plugin:%'
			AND coalesce(json_extract(new.metadata_json,'$.lexical'),1)<>0 BEGIN
			INSERT INTO extractor_fts(rowid,path,title,text) VALUES(new.id,new.path,new.title,new.text);
		END`,
	}
}

func (s *Store) BeginRun(ctx context.Context, runID string) error {
	_, err := s.execContext(ctx,
		`INSERT INTO index_runs(id,started_at_ms,status) VALUES(?,?,'running')`,
		runID, time.Now().UnixMilli())
	return err
}

func (s *Store) FinishRun(ctx context.Context, runID, status string, summary any) error {
	data, err := json.Marshal(summary)
	if err != nil {
		return err
	}
	_, err = s.execContext(ctx,
		`UPDATE index_runs SET finished_at_ms=?, status=?, summary_json=? WHERE id=?`,
		time.Now().UnixMilli(), status, string(data), runID)
	return err
}

// markLegacyExtractionFailures marks files from older runs that logged a
// worker failure without recording which subsequent files were skipped.
func (s *Store) markLegacyExtractionFailures(ctx context.Context) error {
	var marked bool
	if err := s.queryRowContext(ctx, `
		SELECT EXISTS (SELECT 1 FROM meta WHERE key='extraction_retry_v1')`,
	).Scan(&marked); err != nil || marked {
		return err
	}
	tx, err := s.beginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	result, err := tx.ExecContext(ctx, `
		INSERT OR IGNORE INTO meta(key,value) VALUES('extraction_retry_v1','1')`)
	if err != nil {
		return err
	}
	added, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if added > 0 {
		if _, err := tx.ExecContext(ctx, `
			UPDATE files SET extraction_fingerprint=''
			WHERE run_id IN (
				SELECT run_id FROM diagnostics
				WHERE stage GLOB 'extractor-provider:*:start' OR stage GLOB 'extractor-provider:*:extract'
				   OR stage GLOB 'extractor-plugin:*:start' OR stage GLOB 'extractor-plugin:*:extract'
			)`); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// HasIncompleteExtraction reports files with current source records whose
// extractors did not all finish. An empty fingerprint keeps them retryable.
func (s *Store) HasIncompleteExtraction(ctx context.Context) (bool, error) {
	var incomplete bool
	err := s.queryRowContext(ctx, `
		SELECT EXISTS (SELECT 1 FROM files WHERE extraction_fingerprint='')`).Scan(&incomplete)
	return incomplete, err
}

func (s *Store) FileStates(ctx context.Context) (map[string]FileState, error) {
	rows, err := s.queryContext(ctx,
		`SELECT path,size,mtime_ns,content_hash,extraction_fingerprint FROM files ORDER BY path`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := make(map[string]FileState)
	for rows.Next() {
		var state FileState
		if err := rows.Scan(
			&state.Path,
			&state.Size,
			&state.MTimeNS,
			&state.ContentHash,
			&state.ExtractionFingerprint,
		); err != nil {
			return nil, err
		}
		result[state.Path] = state
	}
	return result, rows.Err()
}

func (s *Store) ReplaceFile(
	ctx context.Context,
	runID string,
	file discovery.File,
	contentHash, fingerprint string,
	records []contracts.SearchRecord,
) error {
	tx, err := s.beginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := replaceFile(ctx, tx, runID, FileUpdate{
		File:                  file,
		ContentHash:           contentHash,
		ExtractionFingerprint: fingerprint,
		Records:               records,
	}); err != nil {
		return err
	}
	return tx.Commit()
}

func replaceFile(ctx context.Context, tx *sql.Tx, runID string, update FileUpdate) error {
	file := update.File
	_, err := tx.ExecContext(ctx, `
		INSERT INTO files(path,kind,size,mtime_ns,content_hash,extraction_fingerprint,indexed_at_ms,run_id)
		VALUES(?,?,?,?,?,?,?,?)
		ON CONFLICT(path) DO UPDATE SET
			kind=excluded.kind,size=excluded.size,mtime_ns=excluded.mtime_ns,
			content_hash=excluded.content_hash,
			extraction_fingerprint=excluded.extraction_fingerprint,
			indexed_at_ms=excluded.indexed_at_ms,run_id=excluded.run_id`,
		file.Path, file.Kind, file.Size, file.MTimeNS, update.ContentHash,
		update.ExtractionFingerprint, time.Now().UnixMilli(), runID)
	if err != nil {
		return err
	}
	var fileID int64
	if err := tx.QueryRowContext(ctx, `SELECT id FROM files WHERE path=?`, file.Path).Scan(&fileID); err != nil {
		return err
	}
	carried, err := fileVectors(ctx, tx, []any{fileID})
	if err != nil {
		return err
	}
	if err := deleteFileRecords(ctx, tx, fileID); err != nil {
		return err
	}
	for i, record := range update.Records {
		metadata, err := json.Marshal(record.Metadata)
		if err != nil {
			return fmt.Errorf("encode record metadata: %w", err)
		}
		key := fmt.Sprintf("%s:%s:%d:%d:%s:%d", record.Source, file.Path, record.StartLine, record.EndLine, record.Kind, i)
		if record.ID != "" {
			key = record.Source + ":" + file.Path + ":" + record.ID
		}
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO records(
				record_key,file_id,path,start_line,end_line,kind,title,text,metadata_json,source,input_hash
			) VALUES(?,?,?,?,?,?,?,?,?,?,?)`,
			key, fileID, file.Path, record.StartLine, record.EndLine, record.Kind,
			record.Title, record.Text, string(metadata), record.Source, recordInputHash(record.Title, record.Text)); err != nil {
			return fmt.Errorf("insert record: %w", err)
		}
	}
	return restoreFileVectors(ctx, tx, []any{fileID}, carried)
}

type carriedVector struct {
	vector      []byte
	fingerprint string
}

// fileVectors reads the stored vectors of the given files' embedded records by
// input hash, so records whose embedding input is unchanged keep them after
// their files are re-extracted.
func fileVectors(ctx context.Context, tx *sql.Tx, fileIDs []any) (map[string]carriedVector, error) {
	if len(fileIDs) == 0 {
		return nil, nil
	}
	rows, err := tx.QueryContext(ctx, `
		SELECT e.input_hash,e.fingerprint,v.vector
		FROM records r
		JOIN embedding_records e ON e.record_id=r.id AND e.input_hash=r.input_hash
		JOIN record_vectors v ON v.rowid=r.id
		WHERE r.file_id IN (`+placeholders(len(fileIDs))+`)`, fileIDs...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	carried := make(map[string]carriedVector)
	for rows.Next() {
		var hash string
		var value carriedVector
		if err := rows.Scan(&hash, &value.fingerprint, &value.vector); err != nil {
			return nil, err
		}
		if len(value.vector) > 0 {
			carried[hash] = value
		}
	}
	return carried, rows.Err()
}

func restoreFileVectors(ctx context.Context, tx *sql.Tx, fileIDs []any, carried map[string]carriedVector) error {
	if len(carried) == 0 || len(fileIDs) == 0 {
		return nil
	}
	rows, err := tx.QueryContext(ctx, `
		SELECT id,input_hash FROM records
		WHERE file_id IN (`+placeholders(len(fileIDs))+`) AND kind<>'file'
			AND coalesce(json_extract(metadata_json,'$.semantic'),1)<>0`, fileIDs...)
	if err != nil {
		return err
	}
	type target struct {
		id   int64
		hash string
	}
	var targets []target
	for rows.Next() {
		var value target
		if err := rows.Scan(&value.id, &value.hash); err != nil {
			rows.Close()
			return err
		}
		if _, ok := carried[value.hash]; ok {
			targets = append(targets, value)
		}
	}
	if err := rows.Close(); err != nil {
		return err
	}
	for _, value := range targets {
		saved := carried[value.hash]
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO record_vectors(rowid,vector) VALUES(?,?)`, value.id, saved.vector); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO embedding_records(record_id,input_hash,fingerprint) VALUES(?,?,?)`,
			value.id, value.hash, saved.fingerprint); err != nil {
			return err
		}
	}
	return nil
}

func (s *Store) DeleteFile(ctx context.Context, path string) error {
	tx, err := s.beginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := deleteFile(ctx, tx, path); err != nil {
		return err
	}
	return tx.Commit()
}

func deleteFile(ctx context.Context, tx *sql.Tx, path string) error {
	var fileID int64
	err := tx.QueryRowContext(ctx, `SELECT id FROM files WHERE path=?`, path).Scan(&fileID)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	if err := deleteFileRecords(ctx, tx, fileID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM files WHERE id=?`, fileID); err != nil {
		return err
	}
	return nil
}

func deleteFileRecords(ctx context.Context, tx *sql.Tx, fileID int64) error {
	rows, err := tx.QueryContext(ctx, `SELECT id FROM records WHERE file_id=?`, fileID)
	if err != nil {
		return err
	}
	var ids []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return err
		}
		ids = append(ids, id)
	}
	if err := rows.Close(); err != nil {
		return err
	}
	for _, id := range ids {
		if _, err := tx.ExecContext(ctx, `DELETE FROM record_vectors WHERE rowid=?`, id); err != nil {
			return err
		}
	}
	_, err = tx.ExecContext(ctx, `DELETE FROM records WHERE file_id=?`, fileID)
	return err
}

func (s *Store) AddDiagnostic(ctx context.Context, runID, path, stage, message string) error {
	_, err := s.execContext(ctx, `
		INSERT INTO diagnostics(run_id,path,stage,message,created_at_ms)
		VALUES(?,?,?,?,?)`, runID, path, stage, message, time.Now().UnixMilli())
	return err
}

func (s *Store) ApplyBatch(
	ctx context.Context,
	runID string,
	refreshes []FileRefresh,
	updates []FileUpdate,
	deletes []string,
	diagnostics []Diagnostic,
) error {
	tx, err := s.beginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := bulkRefreshFiles(ctx, tx, runID, refreshes); err != nil {
		return err
	}
	if err := bulkUpsertFiles(ctx, tx, runID, updates); err != nil {
		return err
	}
	paths := make([]string, 0, len(updates)+len(deletes))
	for _, update := range updates {
		paths = append(paths, update.File.Path)
	}
	paths = append(paths, deletes...)
	fileIDs, err := selectFileIDs(ctx, tx, paths)
	if err != nil {
		return err
	}
	updatedIDs := make([]any, 0, len(updates))
	for _, update := range updates {
		if id, ok := fileIDs[update.File.Path]; ok {
			updatedIDs = append(updatedIDs, id)
		}
	}
	carried, err := fileVectors(ctx, tx, updatedIDs)
	if err != nil {
		return err
	}
	if err := bulkDeleteRecords(ctx, tx, fileIDs); err != nil {
		return err
	}
	if err := bulkDeleteFiles(ctx, tx, deletes); err != nil {
		return err
	}
	if err := bulkInsertRecords(ctx, tx, updates, fileIDs); err != nil {
		return err
	}
	if err := restoreFileVectors(ctx, tx, updatedIDs, carried); err != nil {
		return err
	}
	if err := bulkInsertDiagnostics(ctx, tx, runID, diagnostics); err != nil {
		return err
	}
	return tx.Commit()
}

func bulkRefreshFiles(ctx context.Context, tx *sql.Tx, runID string, refreshes []FileRefresh) error {
	if len(refreshes) == 0 {
		return nil
	}
	const refreshesPerStatement = 128
	indexedAt := time.Now().UnixMilli()
	for start := 0; start < len(refreshes); start += refreshesPerStatement {
		end := min(start+refreshesPerStatement, len(refreshes))
		var query strings.Builder
		query.WriteString(`WITH refreshed(path,size,mtime_ns) AS (VALUES `)
		args := make([]any, 0, (end-start)*3+2)
		for i, refresh := range refreshes[start:end] {
			if i > 0 {
				query.WriteByte(',')
			}
			query.WriteString("(?,?,?)")
			args = append(args, refresh.Path, refresh.Size, refresh.MTimeNS)
		}
		query.WriteString(`)
			UPDATE files SET
				size=(SELECT size FROM refreshed WHERE refreshed.path=files.path),
				mtime_ns=(SELECT mtime_ns FROM refreshed WHERE refreshed.path=files.path),
				indexed_at_ms=?,run_id=?
			WHERE path IN (SELECT path FROM refreshed)`)
		args = append(args, indexedAt, runID)
		if _, err := tx.ExecContext(ctx, query.String(), args...); err != nil {
			return fmt.Errorf("refresh files: %w", err)
		}
	}
	return nil
}

func bulkUpsertFiles(ctx context.Context, tx *sql.Tx, runID string, updates []FileUpdate) error {
	if len(updates) == 0 {
		return nil
	}
	var query strings.Builder
	query.WriteString(`
		INSERT INTO files(path,kind,size,mtime_ns,content_hash,extraction_fingerprint,indexed_at_ms,run_id)
		VALUES `)
	args := make([]any, 0, len(updates)*8)
	indexedAt := time.Now().UnixMilli()
	for i, update := range updates {
		if i > 0 {
			query.WriteByte(',')
		}
		query.WriteString("(?,?,?,?,?,?,?,?)")
		file := update.File
		args = append(args,
			file.Path, file.Kind, file.Size, file.MTimeNS, update.ContentHash,
			update.ExtractionFingerprint, indexedAt, runID,
		)
	}
	query.WriteString(`
		ON CONFLICT(path) DO UPDATE SET
			kind=excluded.kind,size=excluded.size,mtime_ns=excluded.mtime_ns,
			content_hash=excluded.content_hash,
			extraction_fingerprint=excluded.extraction_fingerprint,
			indexed_at_ms=excluded.indexed_at_ms,run_id=excluded.run_id`)
	_, err := tx.ExecContext(ctx, query.String(), args...)
	return err
}

func selectFileIDs(ctx context.Context, tx *sql.Tx, paths []string) (map[string]int64, error) {
	result := make(map[string]int64, len(paths))
	if len(paths) == 0 {
		return result, nil
	}
	rows, err := tx.QueryContext(
		ctx,
		`SELECT path,id FROM files WHERE path IN (`+placeholders(len(paths))+`)`,
		stringArgs(paths)...,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var path string
		var id int64
		if err := rows.Scan(&path, &id); err != nil {
			return nil, err
		}
		result[path] = id
	}
	return result, rows.Err()
}

func bulkDeleteRecords(ctx context.Context, tx *sql.Tx, fileIDs map[string]int64) error {
	if len(fileIDs) == 0 {
		return nil
	}
	ids := make([]any, 0, len(fileIDs))
	for _, id := range fileIDs {
		ids = append(ids, id)
	}
	in := placeholders(len(ids))
	if _, err := tx.ExecContext(ctx,
		`DELETE FROM record_vectors
		 WHERE rowid IN (SELECT id FROM records WHERE file_id IN (`+in+`))`,
		ids...,
	); err != nil {
		return err
	}
	_, err := tx.ExecContext(ctx, `DELETE FROM records WHERE file_id IN (`+in+`)`, ids...)
	return err
}

func bulkDeleteFiles(ctx context.Context, tx *sql.Tx, paths []string) error {
	if len(paths) == 0 {
		return nil
	}
	_, err := tx.ExecContext(
		ctx,
		`DELETE FROM files WHERE path IN (`+placeholders(len(paths))+`)`,
		stringArgs(paths)...,
	)
	return err
}

type recordInsert struct {
	key      string
	fileID   int64
	path     string
	record   contracts.SearchRecord
	metadata string
}

func bulkInsertRecords(
	ctx context.Context,
	tx *sql.Tx,
	updates []FileUpdate,
	fileIDs map[string]int64,
) error {
	var records []recordInsert
	for _, update := range updates {
		fileID, ok := fileIDs[update.File.Path]
		if !ok {
			return fmt.Errorf("missing stored file id for %s", update.File.Path)
		}
		for i, record := range update.Records {
			metadata, err := json.Marshal(record.Metadata)
			if err != nil {
				return fmt.Errorf("encode record metadata: %w", err)
			}
			key := fmt.Sprintf(
				"%s:%s:%d:%d:%s:%d",
				record.Source,
				update.File.Path,
				record.StartLine,
				record.EndLine,
				record.Kind,
				i,
			)
			if record.ID != "" {
				key = record.Source + ":" + update.File.Path + ":" + record.ID
			}
			records = append(records, recordInsert{
				key: key, fileID: fileID, path: update.File.Path,
				record: record, metadata: string(metadata),
			})
		}
	}
	const recordsPerStatement = 128
	for start := 0; start < len(records); start += recordsPerStatement {
		end := min(start+recordsPerStatement, len(records))
		var query strings.Builder
		query.WriteString(`
			INSERT INTO records(
				record_key,file_id,path,start_line,end_line,kind,title,text,metadata_json,source,input_hash
			) VALUES `)
		args := make([]any, 0, (end-start)*11)
		for i, item := range records[start:end] {
			if i > 0 {
				query.WriteByte(',')
			}
			query.WriteString("(?,?,?,?,?,?,?,?,?,?,?)")
			record := item.record
			args = append(args,
				item.key, item.fileID, item.path, record.StartLine, record.EndLine,
				record.Kind, record.Title, record.Text, item.metadata, record.Source,
				recordInputHash(record.Title, record.Text),
			)
		}
		if _, err := tx.ExecContext(ctx, query.String(), args...); err != nil {
			return fmt.Errorf("insert records: %w", err)
		}
	}
	return nil
}

func bulkInsertDiagnostics(
	ctx context.Context,
	tx *sql.Tx,
	runID string,
	diagnostics []Diagnostic,
) error {
	if len(diagnostics) == 0 {
		return nil
	}
	var query strings.Builder
	query.WriteString(`
		INSERT INTO diagnostics(run_id,path,stage,message,created_at_ms)
		VALUES `)
	args := make([]any, 0, len(diagnostics)*5)
	createdAt := time.Now().UnixMilli()
	for i, diagnostic := range diagnostics {
		if i > 0 {
			query.WriteByte(',')
		}
		query.WriteString("(?,?,?,?,?)")
		args = append(args, runID, diagnostic.Path, diagnostic.Stage, diagnostic.Message, createdAt)
	}
	_, err := tx.ExecContext(ctx, query.String(), args...)
	return err
}

func placeholders(count int) string {
	if count <= 0 {
		return ""
	}
	return strings.TrimSuffix(strings.Repeat("?,", count), ",")
}

func stringArgs(values []string) []any {
	result := make([]any, len(values))
	for i, value := range values {
		result[i] = value
	}
	return result
}

func (s *Store) Counts(ctx context.Context) (Counts, error) {
	var counts Counts
	for query, target := range map[string]*int{
		`SELECT count(*) FROM files`:       &counts.Files,
		`SELECT count(*) FROM records`:     &counts.Records,
		`SELECT count(*) FROM graph_edges`: &counts.GraphEdges,
		`SELECT count(*) FROM diagnostics`: &counts.Diagnostics,
	} {
		if err := s.queryRowContext(ctx, query).Scan(target); err != nil {
			return Counts{}, err
		}
	}
	return counts, nil
}

// RebuildGraph derives only explicit extractor relationships and mechanically
// resolvable local import/export targets. It does not infer project meaning.
func (s *Store) RebuildGraph(ctx context.Context) error {
	tx, err := s.beginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `DELETE FROM graph_edges`); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO graph_edges(source_path,relation,target,target_path,record_id)
		SELECT path,kind,title,NULL,id
		FROM records
		WHERE source LIKE 'plugin:%' AND title<>''
			AND (kind LIKE '%-import' OR kind LIKE '%-export')
			AND coalesce(json_extract(metadata_json,'$.structural'),0)=0`); err != nil {
		return fmt.Errorf("derive graph edges: %w", err)
	}
	files := make(map[string]bool)
	rows, err := tx.QueryContext(ctx, `SELECT path FROM files`)
	if err != nil {
		return err
	}
	for rows.Next() {
		var path string
		if err := rows.Scan(&path); err != nil {
			rows.Close()
			return err
		}
		files[path] = true
	}
	if err := rows.Close(); err != nil {
		return err
	}
	type unresolvedEdge struct {
		id       int64
		source   string
		relation string
		target   string
	}
	rows, err = tx.QueryContext(ctx, `
		SELECT id,source_path,relation,target FROM graph_edges WHERE target_path IS NULL`)
	if err != nil {
		return err
	}
	var unresolved []unresolvedEdge
	for rows.Next() {
		var edge unresolvedEdge
		if err := rows.Scan(&edge.id, &edge.source, &edge.relation, &edge.target); err != nil {
			rows.Close()
			return err
		}
		unresolved = append(unresolved, edge)
	}
	if err := rows.Close(); err != nil {
		return err
	}
	statement, err := tx.PrepareContext(ctx, `UPDATE graph_edges SET target_path=? WHERE id=?`)
	if err != nil {
		return err
	}
	defer statement.Close()
	modules := pythonModules(files)
	for _, edge := range unresolved {
		target := ""
		if edge.relation == "python-import" {
			target = resolvePythonImport(edge.source, edge.target, files, modules)
		} else if edge.relation == "rust-import" && !strings.HasPrefix(edge.target, ".") {
			target = resolveRustModule(edge.source, edge.target, files)
		} else {
			target = resolveGraphTarget(edge.source, edge.target, files)
		}
		if target != "" {
			if _, err := statement.ExecContext(ctx, target, edge.id); err != nil {
				return err
			}
		}
	}
	return tx.Commit()
}

// pythonModules names each Python file as Python's import system would: the
// module path runs from the nearest ancestor directory without __init__.py.
func pythonModules(files map[string]bool) map[string][]string {
	modules := make(map[string][]string)
	for path := range files {
		if !strings.HasSuffix(path, ".py") {
			continue
		}
		var parts []string
		if stem := strings.TrimSuffix(pathpkg.Base(path), ".py"); stem != "__init__" {
			parts = []string{stem}
		}
		for dir := pathpkg.Dir(path); dir != "." && files[pathpkg.Join(dir, "__init__.py")]; dir = pathpkg.Dir(dir) {
			parts = append([]string{pathpkg.Base(dir)}, parts...)
		}
		if len(parts) > 0 {
			name := strings.Join(parts, ".")
			modules[name] = append(modules[name], path)
		}
	}
	return modules
}

// resolvePythonImport resolves relative imports by package level and absolute
// imports only when exactly one repository file has that module name.
func resolvePythonImport(source, target string, files map[string]bool, modules map[string][]string) string {
	name := strings.TrimLeft(target, ".")
	level := len(target) - len(name)
	if level == 0 {
		if paths := modules[name]; len(paths) == 1 {
			return paths[0]
		}
		return ""
	}
	// A relative import stays inside the package chain: the importing file's
	// directory and every directory it climbs to must be a package.
	dir := pathpkg.Dir(source)
	for step := 0; ; step++ {
		if dir == "." || !files[pathpkg.Join(dir, "__init__.py")] {
			return ""
		}
		if step == level-1 {
			break
		}
		dir = pathpkg.Dir(dir)
	}
	if name == "" {
		return pathpkg.Join(dir, "__init__.py")
	}
	base := pathpkg.Join(dir, strings.ReplaceAll(name, ".", "/"))
	module, pkg := base+".py", pathpkg.Join(base, "__init__.py")
	switch {
	case files[module] && files[pkg]:
		return ""
	case files[module]:
		return module
	case files[pkg]:
		return pkg
	}
	return ""
}

// resolveRustModule resolves a `mod name;` declaration (target is the module
// path, inline parents joined by "/") by Rust's file rules: a declaration in
// mod.rs, lib.rs or main.rs looks beside the file, any other file looks in the
// directory named after its stem. A crate root with another name (a binary,
// example or build script) looks beside the file, so that is tried second. A
// module present as both name.rs and name/mod.rs is ambiguous and unresolved.
func resolveRustModule(source, target string, files map[string]bool) string {
	dir := pathpkg.Dir(source)
	stem := strings.TrimSuffix(pathpkg.Base(source), ".rs")
	dirs := []string{pathpkg.Join(dir, stem), dir}
	if stem == "mod" || stem == "lib" || stem == "main" {
		dirs = dirs[1:]
	}
	for _, base := range dirs {
		file := pathpkg.Join(base, target+".rs")
		module := pathpkg.Join(base, target, "mod.rs")
		switch {
		case files[file] && files[module]:
			return ""
		case files[file]:
			return file
		case files[module]:
			return module
		}
	}
	return ""
}

func resolveGraphTarget(source, target string, files map[string]bool) string {
	if !strings.HasPrefix(target, ".") {
		return ""
	}
	if index := strings.IndexAny(target, "?#"); index >= 0 {
		target = target[:index]
	}
	base := pathpkg.Clean(pathpkg.Join(pathpkg.Dir(source), target))
	candidates := []string{base}
	withoutExtension := strings.TrimSuffix(base, pathpkg.Ext(base))
	extensions := []string{pathpkg.Ext(source), ".ts", ".tsx", ".js", ".jsx", ".go", ".py"}
	seen := map[string]bool{base: true}
	for _, extension := range extensions {
		if extension == "" {
			continue
		}
		for _, candidate := range []string{withoutExtension + extension, pathpkg.Join(base, "index"+extension)} {
			if !seen[candidate] {
				seen[candidate] = true
				candidates = append(candidates, candidate)
			}
		}
	}
	for _, candidate := range candidates {
		if files[candidate] {
			return candidate
		}
	}
	return ""
}

func (s *Store) GraphCandidates(
	ctx context.Context,
	seedPaths []string,
	scope string,
	limit int,
) ([]Record, error) {
	if len(seedPaths) == 0 || limit <= 0 {
		return nil, nil
	}
	args := append(stringArgs(seedPaths), stringArgs(seedPaths)...)
	rows, err := s.queryContext(ctx, `
		SELECT source_path,target_path,relation,record_id
		FROM graph_edges
		WHERE target_path IS NOT NULL AND source_path<>target_path
			AND (source_path IN (`+placeholders(len(seedPaths))+`)
				OR target_path IN (`+placeholders(len(seedPaths))+`))`, args...)
	if err != nil {
		return nil, err
	}
	type neighbor struct {
		score      float64
		evidenceID int64
	}
	neighbors := make(map[string]neighbor)
	seedRanks := make(map[string]int, len(seedPaths))
	for index, path := range seedPaths {
		seedRanks[path] = index + 1
	}
	for rows.Next() {
		var source, target string
		var relation string
		var evidenceID int64
		if err := rows.Scan(&source, &target, &relation, &evidenceID); err != nil {
			rows.Close()
			return nil, err
		}
		path := target
		seedPath := source
		evidence := int64(0)
		if _, targetIsSeed := seedRanks[target]; targetIsSeed {
			path = source
			seedPath = target
			evidence = evidenceID
		}
		if _, pathIsSeed := seedRanks[path]; pathIsSeed || !pathInScope(path, scope) {
			continue
		}
		value := neighbors[path]
		score := graphRelationWeight(relation) / (60 + float64(seedRanks[seedPath]))
		if score > value.score {
			value.score = score
			value.evidenceID = evidence
		} else if score == value.score && value.evidenceID == 0 && evidence != 0 {
			value.evidenceID = evidence
		}
		neighbors[path] = value
	}
	if err := rows.Close(); err != nil {
		return nil, err
	}
	paths := make([]string, 0, len(neighbors))
	for path := range neighbors {
		paths = append(paths, path)
	}
	sort.Slice(paths, func(i, j int) bool {
		if neighbors[paths[i]].score != neighbors[paths[j]].score {
			return neighbors[paths[i]].score > neighbors[paths[j]].score
		}
		return paths[i] < paths[j]
	})
	if len(paths) > limit {
		paths = paths[:limit]
	}
	if len(paths) == 0 {
		return nil, nil
	}
	rows, err = s.queryContext(ctx, `
		SELECT id,input_hash,path,start_line,end_line,kind,title,text,metadata_json,source
		FROM records r
		WHERE path IN (`+placeholders(len(paths))+`)
			AND coalesce(json_extract(metadata_json,'$.lexical'),1)<>0
		ORDER BY path,CASE WHEN kind='file' THEN 1 ELSE 0 END,start_line,id`, stringArgs(paths)...)
	if err != nil {
		return nil, err
	}
	records, err := scanRecords(rows)
	if err != nil {
		return nil, err
	}
	byPath := make(map[string]Record, len(paths))
	for _, record := range records {
		current, exists := byPath[record.Path]
		if !exists || record.ID == neighbors[record.Path].evidenceID || current.Kind == "file" {
			byPath[record.Path] = record
		}
	}
	result := make([]Record, 0, len(paths))
	for _, path := range paths {
		if record, ok := byPath[path]; ok {
			result = append(result, record)
		}
	}
	return result, nil
}

func graphRelationWeight(relation string) float64 {
	switch {
	case strings.HasSuffix(relation, "-import"), strings.HasSuffix(relation, "-export"):
		return 0.6
	default:
		return 0.25
	}
}

func pathInScope(path, scope string) bool {
	scope = strings.TrimSuffix(scope, "/")
	return scope == "" || path == scope || strings.HasPrefix(path, scope+"/")
}

const recordScopePredicate = `(?='' OR r.path=? OR substr(r.path,1,length(?))=?)`
const lexicalRecordPredicate = `coalesce(json_extract(r.metadata_json,'$.lexical'),1)<>0`

func (s *Store) LexicalCandidates(
	ctx context.Context,
	query, scope string,
	limit int,
) ([]Record, error) {
	return uniquePathCandidates(limit, func(recordLimit int) ([]Record, error) {
		rows, err := s.queryContext(ctx, `
			SELECT r.id,r.input_hash,r.path,r.start_line,r.end_line,r.kind,r.title,r.text,r.metadata_json,r.source
			FROM record_fts
			JOIN records r ON r.id=record_fts.rowid
			WHERE record_fts MATCH ? AND `+lexicalRecordPredicate+` AND `+recordScopePredicate+`
			ORDER BY bm25(record_fts,8.0,4.0,1.0),r.path,r.start_line,r.id
			LIMIT ?`, query, scope, scope, scope, scope, recordLimit)
		if err != nil {
			return nil, err
		}
		return scanRecords(rows)
	})
}

func (s *Store) LiteralCandidates(
	ctx context.Context,
	query, literal, scope string,
	limit int,
) ([]Record, error) {
	if query == "" {
		return uniquePathCandidates(limit, func(recordLimit int) ([]Record, error) {
			rows, err := s.queryContext(ctx, `
				SELECT id,input_hash,path,start_line,end_line,kind,title,text,metadata_json,source
				FROM records r
				WHERE r.kind<>'file' AND instr(r.text,?) > 0 AND `+lexicalRecordPredicate+`
					AND `+recordScopePredicate+`
				ORDER BY r.path,r.start_line,r.id
				LIMIT ?`, literal, scope, scope, scope, scope, recordLimit)
			if err != nil {
				return nil, err
			}
			return scanRecords(rows)
		})
	}
	return uniquePathCandidates(limit, func(recordLimit int) ([]Record, error) {
		rows, err := s.queryContext(ctx, `
			SELECT r.id,r.input_hash,r.path,r.start_line,r.end_line,r.kind,r.title,r.text,r.metadata_json,r.source
			FROM record_fts
			JOIN records r ON r.id=record_fts.rowid
			WHERE record_fts MATCH ? AND r.kind<>'file' AND instr(r.text,?) > 0
				AND `+lexicalRecordPredicate+` AND `+recordScopePredicate+`
			ORDER BY bm25(record_fts,8.0,4.0,1.0),r.path,r.start_line,r.id
			LIMIT ?`, query, literal, scope, scope, scope, scope, recordLimit)
		if err != nil {
			return nil, err
		}
		return scanRecords(rows)
	})
}

// LexicalRecords returns mechanically ordered records for an exact matcher.
// An empty FTS query intentionally scans the bounded repository scope so a
// regular expression without stable literal terms remains correct.
func (s *Store) LexicalRecords(
	ctx context.Context,
	query, scope string,
) ([]Record, error) {
	if query == "" {
		rows, err := s.queryContext(ctx, `
			SELECT id,input_hash,path,start_line,end_line,kind,title,text,metadata_json,source
			FROM records r
			WHERE `+lexicalRecordPredicate+` AND `+recordScopePredicate+`
			ORDER BY r.path,r.start_line,r.id`, scope, scope, scope, scope)
		if err != nil {
			return nil, err
		}
		return scanRecords(rows)
	}
	rows, err := s.queryContext(ctx, `
		SELECT r.id,r.input_hash,r.path,r.start_line,r.end_line,r.kind,r.title,r.text,r.metadata_json,r.source
		FROM record_fts
		JOIN records r ON r.id=record_fts.rowid
		WHERE record_fts MATCH ? AND `+lexicalRecordPredicate+` AND `+recordScopePredicate+`
		ORDER BY bm25(record_fts,8.0,4.0,1.0),r.path,r.start_line,r.id`,
		query, scope, scope, scope, scope)
	if err != nil {
		return nil, err
	}
	return scanRecords(rows)
}

// StemmedCandidates matches porter-stemmed tokens, so inflected forms of a
// query term reach the same records.
func (s *Store) StemmedCandidates(
	ctx context.Context,
	query, scope string,
	limit int,
) ([]Record, error) {
	return uniquePathCandidates(limit, func(recordLimit int) ([]Record, error) {
		rows, err := s.queryContext(ctx, `
			SELECT r.id,r.input_hash,r.path,r.start_line,r.end_line,r.kind,r.title,r.text,r.metadata_json,r.source
			FROM stemmed_fts
			JOIN records r ON r.id=stemmed_fts.rowid
			WHERE stemmed_fts MATCH ? AND `+lexicalRecordPredicate+` AND `+recordScopePredicate+`
			ORDER BY bm25(stemmed_fts,8.0,4.0,1.0),r.path,r.start_line,r.id
			LIMIT ?`, query, scope, scope, scope, scope, recordLimit)
		if err != nil {
			return nil, err
		}
		return scanRecords(rows)
	})
}

func (s *Store) ExtractorCandidates(
	ctx context.Context,
	query, scope string,
	limit int,
) ([]Record, error) {
	return uniquePathCandidates(limit, func(recordLimit int) ([]Record, error) {
		rows, err := s.queryContext(ctx, `
			SELECT r.id,r.input_hash,r.path,r.start_line,r.end_line,r.kind,r.title,r.text,r.metadata_json,r.source
			FROM extractor_fts
			JOIN records r ON r.id=extractor_fts.rowid
			WHERE extractor_fts MATCH ? AND `+lexicalRecordPredicate+` AND `+recordScopePredicate+`
			ORDER BY bm25(extractor_fts,8.0,4.0,1.0),r.path,r.start_line,r.id
			LIMIT ?`, query, scope, scope, scope, scope, recordLimit)
		if err != nil {
			return nil, err
		}
		return scanRecords(rows)
	})
}

func uniquePathCandidates(
	limit int,
	load func(recordLimit int) ([]Record, error),
) ([]Record, error) {
	if limit <= 0 {
		return nil, nil
	}
	recordLimit := limit
	for {
		records, err := load(recordLimit)
		if err != nil {
			return nil, err
		}
		unique := firstRecordsByPath(records, limit)
		if len(unique) == limit || len(records) < recordLimit {
			return recordsThroughUniquePathLimit(records, limit), nil
		}
		recordLimit *= 2
	}
}

func (s *Store) PathCandidates(
	ctx context.Context,
	terms []string,
	scope string,
	limit int,
) ([]Record, error) {
	if len(terms) == 0 {
		return nil, nil
	}
	var query strings.Builder
	query.WriteString(`
		SELECT id,input_hash,path,start_line,end_line,kind,title,text,metadata_json,source
		FROM (
			SELECT id,input_hash,path,start_line,end_line,kind,title,text,metadata_json,source,`)
	args := make([]any, 0, len(terms)+5)
	for index, term := range terms {
		if index > 0 {
			query.WriteByte('+')
		}
		query.WriteString(`CASE WHEN instr(lower(path),lower(?)) > 0 THEN 1 ELSE 0 END`)
		args = append(args, term)
	}
	query.WriteString(` AS matches
			FROM records r
			WHERE kind='file' AND ` + recordScopePredicate + `
		)
		WHERE matches > 0
		ORDER BY matches DESC,length(path),path,id
		LIMIT ?`)
	args = append(args, scope, scope, scope, scope)
	args = append(args, limit)
	rows, err := s.queryContext(ctx, query.String(), args...)
	if err != nil {
		return nil, err
	}
	return scanRecords(rows)
}

func scanRecords(rows *sql.Rows) ([]Record, error) {
	defer rows.Close()
	var result []Record
	for rows.Next() {
		var record Record
		var metadata string
		if err := rows.Scan(
			&record.ID,
			&record.InputHash,
			&record.Path,
			&record.StartLine,
			&record.EndLine,
			&record.Kind,
			&record.Title,
			&record.Text,
			&metadata,
			&record.Source,
		); err != nil {
			return nil, err
		}
		if metadata != "" && metadata != "null" {
			if err := json.Unmarshal([]byte(metadata), &record.Metadata); err != nil {
				return nil, fmt.Errorf("decode record metadata: %w", err)
			}
		}
		result = append(result, record)
	}
	return result, rows.Err()
}

func (s *Store) PrepareEmbeddingLane(
	ctx context.Context,
	fingerprint, model string,
	dimensions int,
) (bool, error) {
	var currentFingerprint string
	err := s.queryRowContext(ctx, `SELECT fingerprint FROM embedding_lane WHERE id=1`).Scan(&currentFingerprint)
	if err == nil && currentFingerprint == fingerprint {
		return false, nil
	}
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return false, err
	}
	tx, err := s.beginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `DELETE FROM embedding_records`); err != nil {
		return false, err
	}
	if _, err := tx.ExecContext(ctx, `DROP TABLE IF EXISTS record_vectors`); err != nil {
		return false, err
	}
	if _, err := tx.ExecContext(ctx, `CREATE VIRTUAL TABLE record_vectors USING vec1(vector)`); err != nil {
		return false, err
	}
	if _, err := tx.ExecContext(ctx,
		`INSERT INTO record_vectors(cmd,arg) VALUES('rebuild','{index:"flat",distance:"cos"}')`); err != nil {
		return false, err
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO embedding_lane(id,fingerprint,model,dimensions,updated_at_ms)
		VALUES(1,?,?,?,?)
		ON CONFLICT(id) DO UPDATE SET
			fingerprint=excluded.fingerprint,model=excluded.model,
			dimensions=excluded.dimensions,updated_at_ms=excluded.updated_at_ms`,
		fingerprint, model, dimensions, time.Now().UnixMilli()); err != nil {
		return false, err
	}
	return true, tx.Commit()
}

func (s *Store) MissingEmbeddingInputs(
	ctx context.Context,
	fingerprint string,
	limit int,
) ([]EmbeddingInput, error) {
	return s.MissingEmbeddingInputsByPriority(ctx, fingerprint, limit, EmbeddingPriority{})
}

// MissingEmbeddingInputsByPriority orders derived work by explicit mechanical
// demand. Exact paths retain caller order and precede the requested scope;
// records changed in the current generation precede the remaining corpus.
// Callers can periodically omit priority to age the whole corpus.
func (s *Store) MissingEmbeddingInputsByPriority(
	ctx context.Context,
	fingerprint string,
	limit int,
	priority EmbeddingPriority,
) ([]EmbeddingInput, error) {
	if limit < 1 {
		return nil, errors.New("embedding input limit must be positive")
	}
	paths := make([]string, 0, len(priority.Paths))
	seen := make(map[string]bool, len(priority.Paths))
	for _, path := range priority.Paths {
		path = strings.TrimSpace(path)
		if path == "" || seen[path] {
			continue
		}
		seen[path] = true
		paths = append(paths, path)
	}
	if len(paths) > 100 {
		return nil, errors.New("embedding priority exceeds 100 paths")
	}
	changedPaths := make([]string, 0, len(priority.ChangedPaths))
	for _, path := range priority.ChangedPaths {
		path = strings.TrimSpace(path)
		if path == "" || seen[path] {
			continue
		}
		seen[path] = true
		changedPaths = append(changedPaths, path)
	}
	if len(changedPaths) > 100 {
		return nil, errors.New("embedding changed priority exceeds 100 paths")
	}
	priorityExpr := "0"
	var args []any
	var clauses []string
	for rank, path := range paths {
		clauses = append(clauses, "WHEN r.path=? THEN "+strconv.Itoa(rank))
		args = append(args, path)
	}
	if priority.Scope != "" {
		clauses = append(clauses, "WHEN "+recordScopePredicate+" THEN "+strconv.Itoa(len(paths)))
		args = append(args, priority.Scope, priority.Scope, priority.Scope, priority.Scope)
	}
	changedRank := len(paths)
	if priority.Scope != "" {
		changedRank++
	}
	for offset, path := range changedPaths {
		clauses = append(clauses, "WHEN r.path=? THEN "+strconv.Itoa(changedRank+offset))
		args = append(args, path)
	}
	if len(clauses) > 0 {
		priorityExpr = "CASE " + strings.Join(clauses, " ") + " ELSE " + strconv.Itoa(changedRank+len(changedPaths)) + " END"
	}
	args = append(args, fingerprint, limit)
	rows, err := s.queryContext(ctx, `
		WITH missing AS (
			SELECT r.id,r.input_hash,
				CASE WHEN r.title='' THEN r.text ELSE r.title||char(10)||r.text END AS input_text,
				`+priorityExpr+` AS priority
			FROM records r
			LEFT JOIN embedding_records e
				ON e.record_id=r.id AND e.input_hash=r.input_hash AND e.fingerprint=?
			WHERE r.kind<>'file'
				AND coalesce(json_extract(r.metadata_json,'$.semantic'),1)<>0
				AND e.record_id IS NULL
		)
		SELECT min(id),input_hash,input_text,min(priority) AS priority
		FROM missing
		GROUP BY input_hash,input_text
		ORDER BY priority,length(input_text),min(id)
		LIMIT ?`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var result []EmbeddingInput
	for rows.Next() {
		var input EmbeddingInput
		var priority int
		if err := rows.Scan(&input.ID, &input.InputHash, &input.Text, &priority); err != nil {
			return nil, err
		}
		result = append(result, input)
	}
	return result, rows.Err()
}

func (s *Store) UpsertEmbeddings(
	ctx context.Context,
	fingerprint string,
	dimensions int,
	values []EmbeddingValue,
) (int, error) {
	tx, err := s.beginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	upserted := 0
	for _, value := range values {
		if len(value.Vector) != dimensions {
			return 0, fmt.Errorf("record %d vector has %d dimensions, want %d", value.ID, len(value.Vector), dimensions)
		}
		rows, err := tx.QueryContext(ctx, `
			SELECT id FROM records
			WHERE input_hash=? AND kind<>'file'
				AND coalesce(json_extract(metadata_json,'$.semantic'),1)<>0
			ORDER BY id`, value.InputHash)
		if err != nil {
			return 0, err
		}
		var ids []int64
		representative := false
		for rows.Next() {
			var id int64
			if err := rows.Scan(&id); err != nil {
				rows.Close()
				return 0, err
			}
			ids = append(ids, id)
			representative = representative || id == value.ID
		}
		if err := rows.Close(); err != nil {
			return 0, err
		}
		if !representative {
			return 0, fmt.Errorf("record %d does not match embedding input hash", value.ID)
		}
		for _, id := range ids {
			if _, err := tx.ExecContext(ctx, `DELETE FROM record_vectors WHERE rowid=?`, id); err != nil {
				return 0, err
			}
			if _, err := tx.ExecContext(ctx,
				`INSERT INTO record_vectors(rowid,vector) VALUES(?,?)`,
				id, encodeVector(value.Vector)); err != nil {
				return 0, err
			}
			if _, err := tx.ExecContext(ctx, `
				INSERT INTO embedding_records(record_id,input_hash,fingerprint)
				VALUES(?,?,?)
				ON CONFLICT(record_id) DO UPDATE SET
					input_hash=excluded.input_hash,fingerprint=excluded.fingerprint`,
				id, value.InputHash, fingerprint); err != nil {
				return 0, err
			}
			upserted++
		}
	}
	return upserted, tx.Commit()
}

func (s *Store) EmbeddingState(ctx context.Context, fingerprint string) (EmbeddingState, error) {
	return s.EmbeddingStateForScope(ctx, fingerprint, "")
}

// EmbeddingStateForScope reports compatible vector coverage for one repository
// scope. An empty scope reports repository-wide coverage.
func (s *Store) EmbeddingStateForScope(
	ctx context.Context,
	fingerprint string,
	scope string,
) (EmbeddingState, error) {
	state := EmbeddingState{Fingerprint: fingerprint}
	err := s.queryRowContext(ctx,
		`SELECT model,dimensions FROM embedding_lane WHERE id=1 AND fingerprint=?`,
		fingerprint).Scan(&state.Model, &state.Dimensions)
	if errors.Is(err, sql.ErrNoRows) {
		if err := s.queryRowContext(ctx, `
			SELECT count(*) FROM records r WHERE r.kind<>'file'
				AND coalesce(json_extract(r.metadata_json,'$.semantic'),1)<>0
				AND `+recordScopePredicate,
			scope, scope, scope, scope).Scan(&state.Records); err != nil {
			return EmbeddingState{}, err
		}
		return state, nil
	}
	if err != nil {
		return EmbeddingState{}, err
	}
	state.Configured = true
	if err := s.queryRowContext(ctx, `
		SELECT count(*) FROM records r WHERE r.kind<>'file'
			AND coalesce(json_extract(r.metadata_json,'$.semantic'),1)<>0
			AND `+recordScopePredicate,
		scope, scope, scope, scope).Scan(&state.Records); err != nil {
		return EmbeddingState{}, err
	}
	if err := s.queryRowContext(ctx, `
		SELECT count(*)
		FROM embedding_records e
		JOIN records r ON r.id=e.record_id
		WHERE e.fingerprint=? AND e.input_hash=r.input_hash AND r.kind<>'file'
			AND coalesce(json_extract(r.metadata_json,'$.semantic'),1)<>0
			AND `+recordScopePredicate,
		fingerprint, scope, scope, scope, scope).Scan(&state.Embedded); err != nil {
		return EmbeddingState{}, err
	}
	state.Complete = state.Embedded == state.Records
	return state, nil
}

func (s *Store) VectorCandidates(
	ctx context.Context,
	vector []float32,
	scope string,
	limit int,
) ([]Record, error) {
	vectorLimit := limit
	var total int
	if err := s.queryRowContext(ctx, `
		SELECT count(*) FROM record_vectors v
		JOIN records r ON r.id=v.rowid
		WHERE coalesce(json_extract(r.metadata_json,'$.semantic'),1)<>0`).Scan(&total); err != nil {
		return nil, err
	}
	if total == 0 {
		return nil, nil
	}
	if scope != "" {
		var scoped int
		if err := s.queryRowContext(
			ctx,
			`SELECT count(*) FROM records r WHERE `+recordScopePredicate+`
				AND coalesce(json_extract(r.metadata_json,'$.semantic'),1)<>0`,
			scope, scope, scope, scope,
		).Scan(&scoped); err != nil {
			return nil, err
		}
		if scoped == 0 {
			return nil, nil
		}
		// Start from the scope's mechanical share of records, then expand until
		// enough globally ordered neighbors survive the scope predicate.
		estimated := (total*limit + scoped - 1) / scoped
		vectorLimit = min(total, max(limit, estimated*2))
	}
	for {
		rows, err := s.queryContext(ctx, `
			SELECT r.id,r.input_hash,r.path,r.start_line,r.end_line,r.kind,r.title,r.text,r.metadata_json,r.source
			FROM record_vectors(?,?) v
			JOIN records r ON r.id=v.rowid
			WHERE `+recordScopePredicate+`
				AND coalesce(json_extract(r.metadata_json,'$.semantic'),1)<>0`,
			encodeVector(vector), vectorLimit, scope, scope, scope, scope)
		if err != nil {
			return nil, err
		}
		records, err := scanRecords(rows)
		if err != nil {
			return nil, err
		}
		selected := firstRecordsByPath(records, limit)
		if len(selected) == limit || vectorLimit == total {
			return recordsThroughUniquePathLimit(records, limit), nil
		}
		vectorLimit = min(total, vectorLimit*2)
	}
}

func recordsThroughUniquePathLimit(records []Record, limit int) []Record {
	seen := make(map[string]bool, min(limit, len(records)))
	for index, record := range records {
		if seen[record.Path] {
			continue
		}
		seen[record.Path] = true
		if len(seen) == limit {
			return records[:index+1]
		}
	}
	return records
}

func firstRecordsByPath(records []Record, limit int) []Record {
	seen := make(map[string]bool, min(limit, len(records)))
	result := make([]Record, 0, min(limit, len(records)))
	for _, record := range records {
		if seen[record.Path] {
			continue
		}
		seen[record.Path] = true
		result = append(result, record)
		if len(result) == limit {
			break
		}
	}
	return result
}

func recordInputHash(title, text string) string {
	sum := sha256.Sum256([]byte(title + "\n" + text))
	return "sha256:" + hex.EncodeToString(sum[:])
}

func encodeVector(vector []float32) []byte {
	result := make([]byte, len(vector)*4)
	for i, value := range vector {
		binary.LittleEndian.PutUint32(result[i*4:], math.Float32bits(value))
	}
	return result
}

func (s *Store) LatestRun(ctx context.Context) (*Run, error) {
	var run Run
	var finished sql.NullInt64
	var summary sql.NullString
	err := s.queryRowContext(ctx, `
		SELECT id,started_at_ms,finished_at_ms,status,summary_json
		FROM index_runs
		ORDER BY started_at_ms DESC
		LIMIT 1`).Scan(&run.ID, &run.StartedAtMS, &finished, &run.Status, &summary)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if finished.Valid {
		run.FinishedAtMS = &finished.Int64
	}
	if summary.Valid && summary.String != "" {
		if err := json.Unmarshal([]byte(summary.String), &run.Summary); err != nil {
			return nil, fmt.Errorf("decode run summary: %w", err)
		}
	}
	return &run, nil
}
