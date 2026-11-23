'use client';
import { useEffect, useState } from 'react';
import { Download, Sparkles, X } from 'lucide-react';
import { checkForUpdate, snoozeUpdate, type UpdateInfo } from '@/lib/updates';
import { isNative, openExternal } from '@/lib/native';

/** Shows an in-app notice when a newer APK has been published. Checks on start and when the app returns to the foreground. */
export function UpdateNotice() {
  const [upd, setUpd] = useState<UpdateInfo | null>(null);
  useEffect(() => {
    if (!isNative()) return;
    const run = () => checkForUpdate().then(setUpd).catch(() => {});
    const t = setTimeout(run, 2500); // let the app settle first
    let remove: (() => void) | undefined;
    import('@capacitor/app').then(async ({ App }) => {
      const h = await App.addListener('appStateChange', s => { if (s.isActive) run(); });
      remove = () => h.remove();
    });
    return () => { clearTimeout(t); remove?.(); };
  }, []);
  if (!upd) return null;
  const later = () => { snoozeUpdate(upd); setUpd(null); };
  return (
    <div className="upd-wrap" data-overlay role="dialog" aria-modal="false" aria-labelledby="upd-title">
      <div className="upd-card">
        <button className="upd-x" aria-label="Close" data-close onClick={later}><X size={18} /></button>
        <span className="upd-icon"><Sparkles size={22} /></span>
        <div className="upd-text">
          <b id="upd-title">New update available · <span lang="si">නව යාවත්කාලීනයක්</span></b>
          <small>Version {upd.version} (you have {upd.current}){upd.notes ? ` — ${upd.notes}` : ''}</small>
        </div>
        <div className="upd-actions">
          <button className="primary" onClick={() => { openExternal(upd.url); setUpd(null); }}><Download size={16} /> Update now · <span lang="si">දැන්ම</span></button>
          <button className="secondary" onClick={later}>Later</button>
        </div>
        <p className="upd-hint" lang="si">Download වූ පසු notification එක tap කර <b>Update</b> ඔබන්න. ඔබගේ දත්ත නැති නොවේ.</p>
      </div>
    </div>
  );
}
