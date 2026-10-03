/** Minimaler IndexedDB-Key-Value-Speicher. Es werden nur verschlüsselte Daten abgelegt (plus der Kontoname). */
const DB = 'chat';
const STORE = 'kv';

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const r = indexedDB.open(DB, 1);
    r.onupgradeneeded = () => r.result.createObjectStore(STORE);
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

async function tx<T>(mode: IDBTransactionMode, f: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await open();
  return new Promise((resolve, reject) => {
    const r = f(db.transaction(STORE, mode).objectStore(STORE));
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

export const kv = {
  get: <T>(k: string) => tx<T | undefined>('readonly', (s) => s.get(k)),
  put: (k: string, v: unknown) => tx('readwrite', (s) => s.put(v, k)),
  del: (k: string) => tx('readwrite', (s) => s.delete(k)),
};
