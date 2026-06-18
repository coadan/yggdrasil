function trimTrailingSlash(value: string): string {
  return value.replace(/\/+$/, "");
}

export function artifactBaseFromLocation(location: Location = window.location): string {
  const reportDir = new URLSearchParams(location.search).get("reportDir")?.trim();
  if (!reportDir) {
    return "";
  }

  const normalized = trimTrailingSlash(reportDir);
  if (normalized.startsWith("/@fs/") || /^https?:\/\//.test(normalized)) {
    return normalized;
  }
  if (normalized.startsWith("/")) {
    return `/@fs${normalized}`;
  }
  return normalized;
}

export function artifactPath(base: string, file: string): string {
  return base ? `${trimTrailingSlash(base)}/${file}` : file;
}

export async function loadJson<T>(path: string): Promise<T> {
  const response = await fetch(path, { cache: "no-store" });
  if (!response.ok) {
    throw new Error(`Could not load ${path}: ${response.status}`);
  }
  return (await response.json()) as T;
}
