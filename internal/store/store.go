// Package store owns the complete SQLite persistence boundary.
package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"time"

	"github.com/coadan/yggdrasil/internal/contracts"
	"github.com/coadan/yggdrasil/internal/discovery"
	"github.com/ncruces/go-sqlite3/driver"
	"github.com/ncruces/go-sqlite3/ext/fts5"
)

const schemaVersion = "1"

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

type Counts struct {
	Files       int `json:"files"`
	Records     int `json:"records"`
	Diagnostics int `json:"diagnostics"`
}

type Record struct {
	ID        int64
	Path      string
	StartLine int
	EndLine   int
	Kind      string
	Title     string
	Text      string
	Metadata  map[string]any
	Source    string
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
	uri := (&url.URL{Scheme: "file", Path: path}).String()
	db, err := driver.Open(uri+"?_pragma=busy_timeout(1000)", fts5.Register)
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
		)`,
		`CREATE VIRTUAL TABLE IF NOT EXISTS record_fts USING fts5(
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
		END`,
		`CREATE TRIGGER IF NOT EXISTS records_au AFTER UPDATE ON records BEGIN
			INSERT INTO record_fts(record_fts, rowid, path, title, text)
			VALUES ('delete', old.id, old.path, old.title, old.text);
			INSERT INTO record_fts(rowid, path, title, text)
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

func (s *Store) FileState(ctx context.Context, path string) (FileState, bool, error) {
	var state FileState
	err := s.db.QueryRowContext(ctx,
		`SELECT path,size,mtime_ns,content_hash,extraction_fingerprint FROM files WHERE path=?`,
		path).Scan(&state.Path, &state.Size, &state.MTimeNS, &state.ContentHash, &state.ExtractionFingerprint)
	if errors.Is(err, sql.ErrNoRows) {
		return FileState{}, false, nil
	}
	return state, err == nil, err
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
	_, err = tx.ExecContext(ctx, `
		INSERT INTO files(path,kind,size,mtime_ns,content_hash,extraction_fingerprint,indexed_at_ms,run_id)
		VALUES(?,?,?,?,?,?,?,?)
		ON CONFLICT(path) DO UPDATE SET
			kind=excluded.kind,size=excluded.size,mtime_ns=excluded.mtime_ns,
			content_hash=excluded.content_hash,
			extraction_fingerprint=excluded.extraction_fingerprint,
			indexed_at_ms=excluded.indexed_at_ms,run_id=excluded.run_id`,
		file.Path, file.Kind, file.Size, file.MTimeNS, contentHash, fingerprint, time.Now().UnixMilli(), runID)
	if err != nil {
		return err
	}
	var fileID int64
	if err := tx.QueryRowContext(ctx, `SELECT id FROM files WHERE path=?`, file.Path).Scan(&fileID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM records WHERE file_id=?`, fileID); err != nil {
		return err
	}
	for i, record := range records {
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
				record_key,file_id,path,start_line,end_line,kind,title,text,metadata_json,source
			) VALUES(?,?,?,?,?,?,?,?,?,?)`,
			key, fileID, file.Path, record.StartLine, record.EndLine, record.Kind,
			record.Title, record.Text, string(metadata), record.Source); err != nil {
			return fmt.Errorf("insert record: %w", err)
		}
	}
	return tx.Commit()
}

func (s *Store) DeleteFile(ctx context.Context, path string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM files WHERE path=?`, path)
	return err
}

func (s *Store) FilePaths(ctx context.Context) ([]string, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT path FROM files ORDER BY path`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var result []string
	for rows.Next() {
		var path string
		if err := rows.Scan(&path); err != nil {
			return nil, err
		}
		result = append(result, path)
	}
	return result, rows.Err()
}

func (s *Store) AddDiagnostic(ctx context.Context, runID, path, stage, message string) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO diagnostics(run_id,path,stage,message,created_at_ms)
		VALUES(?,?,?,?,?)`, runID, path, stage, message, time.Now().UnixMilli())
	return err
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

func (s *Store) LexicalCandidates(ctx context.Context, query string, limit int) ([]Record, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT r.id,r.path,r.start_line,r.end_line,r.kind,r.title,r.text,r.metadata_json,r.source
		FROM record_fts
		JOIN records r ON r.id=record_fts.rowid
		WHERE record_fts MATCH ?
		ORDER BY bm25(record_fts,8.0,4.0,1.0),r.path,r.start_line,r.id
		LIMIT ?`, query, limit)
	if err != nil {
		return nil, err
	}
	return scanRecords(rows)
}

func (s *Store) PathCandidates(ctx context.Context, query string, limit int) ([]Record, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id,path,start_line,end_line,kind,title,text,metadata_json,source
		FROM records
		WHERE kind='file' AND instr(lower(path),lower(?)) > 0
		ORDER BY
			CASE WHEN lower(path)=lower(?) THEN 0 ELSE 1 END,
			length(path),path,id
		LIMIT ?`, query, query, limit)
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
