import { useEffect, type ReactNode } from 'react';
import { Icon } from './Icon';

export function Dialog({ title, onClose, children, wide }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  useEffect(() => {
    const h = (ev: KeyboardEvent) => ev.key === 'Escape' && onClose();
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);
  return (
    <div className="overlay" onClick={onClose}>
      <div className={`dialog card ${wide ? 'wide' : ''}`} role="dialog" aria-modal="true" aria-label={title} onClick={(x) => x.stopPropagation()}>
        <header><h2>{title}</h2><button className="icon-btn" onClick={onClose} aria-label="Schließen" title="Schließen"><Icon name="close" /></button></header>
        {children}
      </div>
    </div>
  );
}
