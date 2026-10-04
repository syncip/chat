import { useEffect, useMemo, useState } from 'react';
import { Engine } from './lib/engine';
import { EngineContext, useEngine } from './ui/hooks';
import { Onboarding, Unlock } from './ui/Auth';
import { Main } from './ui/Main';
import { loadSession, clearSession } from './lib/session';
import { PublicChannelView, parsePublicLink } from './ui/PublicChannel';

export function App() {
  const engine = useMemo(() => new Engine(), []);
  const [boot, setBoot] = useState<{ address: string } | null | 'loading'>('loading');
  useEffect(() => {
    engine
      .init()
      .then(async (meta) => {
        if (meta) {
          // „Angemeldet bleiben“: gespeicherte Sitzung nutzen, falls noch gültig
          const p = await loadSession();
          if (p) await engine.unlock(p).catch(() => clearSession());
        }
        setBoot(meta);
      })
      .catch((e) => {
        console.error(e);
        setBoot(null);
      });
  }, [engine]);
  return (
    <EngineContext.Provider value={engine}>
      <Shell loading={boot === 'loading'} />
    </EngineContext.Provider>
  );
}

function Shell({ loading }: { loading: boolean }) {
  const e = useEngine();
  const [hash, setHash] = useState(location.hash);
  useEffect(() => {
    const h = () => setHash(location.hash);
    window.addEventListener('hashchange', h);
    return () => window.removeEventListener('hashchange', h);
  }, []);
  const pub = parsePublicLink(hash);
  if (pub) return <PublicChannelView link={pub} onClose={() => setHash('')} />; // ohne Konto lesbar
  if (loading) return <div className="center muted">Lade …</div>;
  if (e.unlocked) return <Main />;
  if (e.knownAddress) return <Unlock address={e.knownAddress} />;
  return <Onboarding />;
}
