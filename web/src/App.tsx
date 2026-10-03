import { useEffect, useMemo, useState } from 'react';
import { Engine } from './lib/engine';
import { EngineContext, useEngine } from './ui/hooks';
import { Onboarding, Unlock } from './ui/Auth';
import { Main } from './ui/Main';

export function App() {
  const engine = useMemo(() => new Engine(), []);
  const [boot, setBoot] = useState<{ address: string } | null | 'loading'>('loading');
  useEffect(() => {
    engine.init().then(setBoot, (e) => {
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
  if (loading) return <div className="center muted">Lade …</div>;
  if (e.unlocked) return <Main />;
  if (e.knownAddress) return <Unlock address={e.knownAddress} />;
  return <Onboarding />;
}
