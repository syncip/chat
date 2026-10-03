export interface Cap {
  domain: string;
  mailbox_id: string;
  send_token: string;
  key: string; // base64, Umschlag-Schlüssel
  /** true: nur Intro-Postfach (Platzhalter bis zur Selbst-Ankündigung des Kontakts) */
  intro?: boolean;
  /** Gerät, dem das Postfach gehört (jedes Gerät hat eigene Unterhaltungs-Postfächer). */
  device?: string;
}

export type Part =
  | { type: 'text'; body: string }
  | { type: 'code'; lang: string; body: string }
  | { type: 'quote'; reference: string; snippet: string }
  | {
      type: 'file';
      blob_id: string;
      blob_server: string;
      key: string;
      nonce: string;
      name: string;
      mime: string;
      size: number;
      sha256: string;
    };

export interface CapEntry extends Cap {
  address: string;
  device: string;
}

export type Content =
  | { kind: 'message'; parts: Part[]; once?: boolean }
  | { kind: 'reaction'; reference: string; emoji: string }
  | { kind: 'edit'; reference: string; parts: Part[] }
  | { kind: 'delete'; reference: string }
  | { kind: 'receipt'; receipt: 'delivered' | 'read'; references: string[] }
  | { kind: 'disappear'; seconds: number }
  | { kind: 'directory'; entries: CapEntry[] }
  | { kind: 'group_name'; name: string };

export interface Envelope {
  v: 1;
  id: string;
  ts: number;
  content: Content;
}

export interface Msg {
  id: string;
  from: string;
  ts: number;
  parts: Part[];
  /** Eigene Nachrichten: sending → sent (Server hat angenommen) → delivered → read. */
  status: 'sending' | 'sent' | 'delivered' | 'read' | 'failed' | 'received';
  /** Einmal-Nachricht: nach dem ersten Anzeigen gelöscht. */
  once?: boolean;
  consumed?: boolean;
  /** Eingehend: Lesebestätigung wurde bereits gesendet. */
  readAck?: boolean;
  edited?: boolean;
  deleted?: boolean;
  reactions: Record<string, string[]>;
  expiresAt?: number;
}

export interface Conversation {
  id: string; // hex der MLS-Gruppen-ID
  kind: 'dm' | 'group';
  title: string;
  status: 'active' | 'request' | 'left';
  /** Ein Eintrag je Gerät (MLS-Blatt); `ik` = Konto-Schlüssel (AIK). */
  members: { address: string; ik: string; device: string }[];
  caps: Record<string, Cap>; // address → Postfach, an das wir senden
  myMailbox?: { id: string; key: string; token: string };
  messages: Msg[];
  unread: number;
  disappearSeconds: number;
  warning?: string;
  /** Gerät wurde per Welcome aus dem eigenen Konto aufgenommen: Postfach erst ankündigen, wenn die Verzeichnisse da sind. */
  pendingAnnounce?: boolean;
  createdAt: number;
}

export interface Contact {
  address: string;
  ik: string; // hex, angepinnt (TOFU)
  verified: boolean;
  intro?: Cap;
}

export type FilterMode = 'off' | 'block' | 'allow';

export interface AppState {
  v: 1;
  me: { address: string; domain: string; name: string; deviceId: string; inboxId: string };
  /** Backup-Datei wurde gespeichert (Pflicht nach der Registrierung). */
  backupDone: boolean;
  intro: { mailbox_id: string; send_token: string; key: string } | null;
  conversations: Record<string, Conversation>;
  contacts: Record<string, Contact>;
  /** Postfach-ID → Umschlag-Schlüssel (base64) */
  mailboxes: Record<string, string>;
  blockedUsers: string[];
  blockedServers: string[];
  allowUsers: string[];
  allowServers: string[];
  filterMode: FilterMode;
  serverSideFilter: boolean;
  directSend: boolean;
  cursor: number;
  outbox: { id: string; cap: Cap; blob: string; tries: number }[];
  /** Bestätigungen senden (Standard: aus; wer sie ausschaltet, sieht die der anderen auch nicht). */
  sendDelivered: boolean;
  sendRead: boolean;
  /** Einmal-Nachrichten: eigene Kopie sofort entfernen. */
  onceDropOwnCopy: boolean;
}

export interface ServerInfo {
  domain: string;
  version: number;
  registration: 'invite' | 'open' | 'closed';
  federation: string;
  client_hash: string;
  pow_bits: number;
  limits: {
    max_file_size: number;
    max_message_attachments: number;
    max_message_total_size: number;
    max_message_text: number;
    max_envelope_size: number;
    user_quota: number;
    blob_retention_days: number;
    message_retention_days: number;
  };
}

export interface DeviceInfo {
  id: string;
  created_at: number;
  current: boolean;
}
