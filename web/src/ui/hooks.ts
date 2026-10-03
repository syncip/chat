import { createContext, useContext, useSyncExternalStore } from 'react';
import type { Engine } from '../lib/engine';

export const EngineContext = createContext<Engine | null>(null);

/** Gibt die Engine zurück und rendert bei jeder Zustandsänderung neu. */
export function useEngine(): Engine {
  const e = useContext(EngineContext);
  if (!e) throw new Error('no engine');
  useSyncExternalStore(e.subscribe, e.getVersion);
  return e;
}
