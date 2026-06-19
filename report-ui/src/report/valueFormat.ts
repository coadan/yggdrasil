const identityKeys = ["label", "name", "id", "xt/id", "path", "target", "source", "import", "package-name"];

function primitive(value: unknown): string {
  if (value === null || value === undefined || value === "") return "";
  return String(value);
}

function truncate(value: string, max = 180): string {
  return value.length > max ? `${value.slice(0, max - 1)}…` : value;
}

function objectLabel(record: Record<string, unknown>): string {
  for (const key of identityKeys) {
    const value = record[key];
    if (typeof value === "string" && value.trim()) return value;
    if (typeof value === "number") return String(value);
  }
  return "";
}

function objectSummary(record: Record<string, unknown>): string {
  const label = objectLabel(record);
  const kind = primitive(record.kind || record.relation || record.type);
  const path = primitive(record.path || record["path-prefix"] || record.repo || record["repo-id"]);
  const parts = [label, kind, path].filter(Boolean);
  if (parts.length > 0) return parts.join(" / ");
  return truncate(JSON.stringify(record));
}

export function displayValue(value: unknown): string {
  if (value === null || value === undefined || value === "") return "";
  if (Array.isArray(value)) {
    const visible = value
      .slice(0, 4)
      .map((item) => displayValue(item))
      .filter(Boolean);
    const suffix = value.length > 4 ? ` +${value.length - 4} more` : "";
    return `${visible.join(", ")}${suffix}`;
  }
  if (typeof value === "object") return objectSummary(value as Record<string, unknown>);
  return String(value);
}
