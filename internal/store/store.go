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
	"path/filepath"
	"strings"
	"time"

	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/discovery"
	"github.com/ncruces/go-sqlite3"
	"github.com/ncruces/go-sqlite3/driver"
	"github.com/ncruces/go-sqlite3/ext/fts5"
	"github.com/ncruces/go-sqlite3/ext/vec1"
)

const schemaVersion = "2"
const indexFreshnessKey = "index_freshness_token"

type Store struct {
	db   *sql.DB
	path string
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
	Diagnostics int `json:"diagnostics"`
}

type Record struct {
	ID        int64
	InputHash string
	Path      string
	StartLine int
	EndLine   int
	Kind      string
	Title     string
	Text      string
	Metadata  map[string]any
	Source    string
}

type EmbeddingInput struct {
	ID        int64
	InputHash string
	Text      string
}

type EmbeddingValue struct {
	ID        int64
	InputHash string
	Vector    []float32
}

type EmbeddingState struct {
	Configured  bool   `json:"configured"`
	Fingerprint string `json:"fingerprint,omitempty"`
	Model       string `json:"model,omitempty"`
	Dimensions  int    `json:"dimensions,omitempty"`
	Embedded    int    `json:"embedded"`
	Records     int    `json:"records"`
	Complete    bool   `json:"complete"`
}

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
	err := s.db.QueryRowContext(
		ctx, `SELECT value FROM meta WHERE key='worktree_seed_source'`,
	).Scan(&source)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return source, err
}

func (s *Store) CompleteSeed(ctx context.Context) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM meta WHERE key='worktree_seed_source'`)
	return err
}

func (s *Store) IndexFreshnessToken(ctx context.Context) (string, error) {
	var token string
	err := s.db.QueryRowContext(
		ctx, `SELECT value FROM meta WHERE key=?`, indexFreshnessKey,
	).Scan(&token)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return token, err
}

func (s *Store) SetIndexFreshnessToken(ctx context.Context, token string) error {
	if token == "" {
		_, err := s.db.ExecContext(ctx, `DELETE FROM meta WHERE key=?`, indexFreshnessKey)
		return err
	}
	_, err := s.db.ExecContext(ctx, `
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
	return s.db.Close()
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
		`CREATE VIRTUAL TABLE IF NOT EXISTS extractor_fts USING fts5(
			path, title, text,
			content='records',
			content_rowid='id',
			tokenize='unicode61'
		)`,
		`CREATE TRIGGER IF NOT EXISTS records_ai AFTER INSERT ON records BEGIN
			INSERT INTO record_fts(rowid, path, title, text)
			VALUES (new.id, new.path, new.title, new.text);
		END`,
		`CREATE TRIGGER IF NOT EXISTS records_ad AFTER DELETE ON records BEGIN
			INSERT INTO record_fts(record_fts, rowid, path, title, text)
			VALUES ('delete', old.id, old.path, old.title, old.text);
			DELETE FROM embedding_records WHERE record_id=old.id;
		END`,
		`CREATE TRIGGER IF NOT EXISTS records_au AFTER UPDATE ON records BEGIN
			INSERT INTO record_fts(record_fts, rowid, path, title, text)
			VALUES ('delete', old.id, old.path, old.title, old.text);
			INSERT INTO record_fts(rowid, path, title, text)
			VALUES (new.id, new.path, new.title, new.text);
		END`,
		`CREATE TRIGGER IF NOT EXISTS records_extractor_ai AFTER INSERT ON records
		WHEN new.source LIKE 'plugin:%' BEGIN
			INSERT INTO extractor_fts(rowid, path, title, text)
			VALUES (new.id, new.path, new.title, new.text);
		END`,
		`CREATE TRIGGER IF NOT EXISTS records_extractor_ad AFTER DELETE ON records
		WHEN old.source LIKE 'plugin:%' BEGIN
			INSERT INTO extractor_fts(extractor_fts, rowid, path, title, text)
			VALUES ('delete', old.id, old.path, old.title, old.text);
		END`,
		`CREATE TRIGGER IF NOT EXISTS records_extractor_au_delete AFTER UPDATE ON records
		WHEN old.source LIKE 'plugin:%' BEGIN
			INSERT INTO extractor_fts(extractor_fts, rowid, path, title, text)
			VALUES ('delete', old.id, old.path, old.title, old.text);
		END`,
		`CREATE TRIGGER IF NOT EXISTS records_extractor_au_insert AFTER UPDATE ON records
		WHEN new.source LIKE 'plugin:%' BEGIN
			INSERT INTO extractor_fts(rowid, path, title, text)
			VALUES (new.id, new.path, new.title, new.text);
		END`,
		`CREATE TABLE IF NOT EXISTS diagnostics (
			id INTEGER PRIMARY KEY,
			run_id TEXT NOT NULL,
			path TEXT,
			stage TEXT NOT NULL,
			message TEXT NOT NULL,
			created_at_ms INTEGER NOT NULL
		)`,
	}
	for _, statement := range statements {
		if _, err := s.db.ExecContext(ctx, statement); err != nil {
			return fmt.Errorf("initialize sqlite: %w", err)
		}
	}
	var version string
	err := s.db.QueryRowContext(ctx, `SELECT value FROM meta WHERE key='schema_version'`).Scan(&version)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		if _, err := s.db.ExecContext(ctx, `INSERT INTO meta(key,value) VALUES('schema_version',?)`, schemaVersion); err != nil {
			return err
		}
	case err != nil:
		return err
	case version != schemaVersion:
		return fmt.Errorf("index schema %s is incompatible with %s; run ygg index --full", version, schemaVersion)
	}
	var extractorFTSVersion string
	err = s.db.QueryRowContext(
		ctx, `SELECT value FROM meta WHERE key='extractor_fts_version'`,
	).Scan(&extractorFTSVersion)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		if _, err := s.db.ExecContext(ctx, `
			INSERT INTO extractor_fts(rowid,path,title,text)
			SELECT id,path,title,text FROM records WHERE source LIKE 'plugin:%'`); err != nil {
			return fmt.Errorf("build extractor index: %w", err)
		}
		if _, err := s.db.ExecContext(
			ctx, `INSERT INTO meta(key,value) VALUES('extractor_fts_version','1')`,
		); err != nil {
			return err
		}
	case err != nil:
		return err
	case extractorFTSVersion != "1":
		return fmt.Errorf("unsupported extractor index version %s", extractorFTSVersion)
	}
	var storedID, storedRoot string
	err = s.db.QueryRowContext(ctx, `SELECT root_id, root FROM repository WHERE id=1`).Scan(&storedID, &storedRoot)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		_, err = s.db.ExecContext(ctx, `INSERT INTO repository(id,root_id,root) VALUES(1,?,?)`, rootID, root)
		return err
	case err != nil:
		return err
	case storedID != rootID || storedRoot != root:
		return fmt.Errorf("index belongs to repository %s at %s", storedID, storedRoot)
	default:
		return nil
	}
}

func (s *Store) BeginRun(ctx context.Context, runID string) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO index_runs(id,started_at_ms,status) VALUES(?,?,'running')`,
		runID, time.Now().UnixMilli())
	return err
}

func (s *Store) FinishRun(ctx context.Context, runID, status string, summary any) error {
	data, err := json.Marshal(summary)
	if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx,
		`UPDATE index_runs SET finished_at_ms=?, status=?, summary_json=? WHERE id=?`,
		time.Now().UnixMilli(), status, string(data), runID)
	return err
}

func (s *Store) FileStates(ctx context.Context) (map[string]FileState, error) {
	rows, err := s.db.QueryContext(ctx,
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
	tx, err := s.db.BeginTx(ctx, nil)
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
	return nil
}

func (s *Store) DeleteFile(ctx context.Context, path string) error {
	tx, err := s.db.BeginTx(ctx, nil)
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
	_, err := s.db.ExecContext(ctx, `
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
	tx, err := s.db.BeginTx(ctx, nil)
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
	if err := bulkDeleteRecords(ctx, tx, fileIDs); err != nil {
		return err
	}
	if err := bulkDeleteFiles(ctx, tx, deletes); err != nil {
		return err
	}
	if err := bulkInsertRecords(ctx, tx, updates, fileIDs); err != nil {
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
		`SELECT count(*) FROM diagnostics`: &counts.Diagnostics,
	} {
		if err := s.db.QueryRowContext(ctx, query).Scan(target); err != nil {
			return Counts{}, err
		}
	}
	return counts, nil
}

const recordScopePredicate = `(?='' OR r.path=? OR substr(r.path,1,length(?))=?)`

func (s *Store) LexicalCandidates(
	ctx context.Context,
	query, scope string,
	limit int,
) ([]Record, error) {
	return uniquePathCandidates(limit, func(recordLimit int) ([]Record, error) {
		rows, err := s.db.QueryContext(ctx, `
			SELECT r.id,r.input_hash,r.path,r.start_line,r.end_line,r.kind,r.title,r.text,r.metadata_json,r.source
			FROM record_fts
			JOIN records r ON r.id=record_fts.rowid
			WHERE record_fts MATCH ? AND `+recordScopePredicate+`
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
	return uniquePathCandidates(limit, func(recordLimit int) ([]Record, error) {
		rows, err := s.db.QueryContext(ctx, `
			SELECT r.id,r.input_hash,r.path,r.start_line,r.end_line,r.kind,r.title,r.text,r.metadata_json,r.source
			FROM record_fts
			JOIN records r ON r.id=record_fts.rowid
			WHERE record_fts MATCH ? AND instr(r.text,?) > 0 AND `+recordScopePredicate+`
			ORDER BY bm25(record_fts,8.0,4.0,1.0),r.path,r.start_line,r.id
			LIMIT ?`, query, literal, scope, scope, scope, scope, recordLimit)
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
		rows, err := s.db.QueryContext(ctx, `
			SELECT r.id,r.input_hash,r.path,r.start_line,r.end_line,r.kind,r.title,r.text,r.metadata_json,r.source
			FROM extractor_fts
			JOIN records r ON r.id=extractor_fts.rowid
			WHERE extractor_fts MATCH ? AND `+recordScopePredicate+`
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
		seen := make(map[string]bool, min(limit, len(records)))
		unique := make([]Record, 0, min(limit, len(records)))
		for _, record := range records {
			if seen[record.Path] {
				continue
			}
			seen[record.Path] = true
			unique = append(unique, record)
			if len(unique) == limit {
				return unique, nil
			}
		}
		if len(records) < recordLimit {
			return unique, nil
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
	rows, err := s.db.QueryContext(ctx, query.String(), args...)
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
	err := s.db.QueryRowContext(ctx, `SELECT fingerprint FROM embedding_lane WHERE id=1`).Scan(&currentFingerprint)
	if err == nil && currentFingerprint == fingerprint {
		return false, nil
	}
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return false, err
	}
	tx, err := s.db.BeginTx(ctx, nil)
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
	rows, err := s.db.QueryContext(ctx, `
		WITH missing AS (
			SELECT r.id,r.input_hash,
				CASE WHEN r.title='' THEN r.text ELSE r.title||char(10)||r.text END AS input_text
			FROM records r
			LEFT JOIN embedding_records e
				ON e.record_id=r.id AND e.input_hash=r.input_hash AND e.fingerprint=?
			WHERE r.kind<>'file' AND e.record_id IS NULL
		)
		SELECT min(id),input_hash,input_text
		FROM missing
		GROUP BY input_hash,input_text
		ORDER BY length(input_text),min(id)
		LIMIT ?`, fingerprint, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var result []EmbeddingInput
	for rows.Next() {
		var input EmbeddingInput
		if err := rows.Scan(&input.ID, &input.InputHash, &input.Text); err != nil {
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
	tx, err := s.db.BeginTx(ctx, nil)
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
	state := EmbeddingState{Fingerprint: fingerprint}
	err := s.db.QueryRowContext(ctx,
		`SELECT model,dimensions FROM embedding_lane WHERE id=1 AND fingerprint=?`,
		fingerprint).Scan(&state.Model, &state.Dimensions)
	if errors.Is(err, sql.ErrNoRows) {
		if err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM records WHERE kind<>'file'`).Scan(&state.Records); err != nil {
			return EmbeddingState{}, err
		}
		return state, nil
	}
	if err != nil {
		return EmbeddingState{}, err
	}
	state.Configured = true
	if err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM records WHERE kind<>'file'`).Scan(&state.Records); err != nil {
		return EmbeddingState{}, err
	}
	if err := s.db.QueryRowContext(ctx, `
		SELECT count(*)
		FROM embedding_records e
		JOIN records r ON r.id=e.record_id
		WHERE e.fingerprint=? AND e.input_hash=r.input_hash AND r.kind<>'file'`,
		fingerprint).Scan(&state.Embedded); err != nil {
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
	if err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM record_vectors`).Scan(&total); err != nil {
		return nil, err
	}
	if total == 0 {
		return nil, nil
	}
	if scope != "" {
		var scoped int
		if err := s.db.QueryRowContext(
			ctx,
			`SELECT count(*) FROM records r WHERE `+recordScopePredicate,
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
		rows, err := s.db.QueryContext(ctx, `
			SELECT r.id,r.input_hash,r.path,r.start_line,r.end_line,r.kind,r.title,r.text,r.metadata_json,r.source
			FROM record_vectors(?,?) v
			JOIN records r ON r.id=v.rowid
			WHERE `+recordScopePredicate,
			encodeVector(vector), vectorLimit, scope, scope, scope, scope)
		if err != nil {
			return nil, err
		}
		records, err := scanRecords(rows)
		if err != nil {
			return nil, err
		}
		unique := firstRecordsByPath(records, limit)
		if len(unique) == limit || vectorLimit == total {
			return unique, nil
		}
		vectorLimit = min(total, vectorLimit*2)
	}
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
	err := s.db.QueryRowContext(ctx, `
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
