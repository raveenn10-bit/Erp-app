// Web: same-origin Worker. Android: bundled app → NEXT_PUBLIC_API_URL over
// native HTTP, with an encrypted on-device cache for reads and a durable
// outbox for idempotent writes when the server can't be reached.
import { apiBase, isNativeApp, NetworkError, rawRequest } from './http';

export { apiBase };
/** Fired when the server says the session is gone, so the UI can return to login. */
export const SESSION_EXPIRED_EVENT='erp-session-expired';

export class ApiError extends Error { constructor(msg:string, public status:number){ super(msg); this.name='ApiError'; } }

export async function api<T>(path:string,init:RequestInit={}):Promise<T>{
  const method=(init.method||'GET').toUpperCase();
  const native=isNativeApp();
  const offline=native?await import('./offline/store'):null;
  let r;
  try{
    if(offline&&!offline.getSyncState().online)throw new NetworkError('Offline');
    r=await rawRequest(path,init);
  }catch(e){
    if(!(e instanceof NetworkError))throw e;
    if(offline){
      if(method==='GET'){
        const cached=await offline.cacheGet<T>(path).catch(()=>null);
        if(cached!==null)return cached;
      }else if(typeof init.body==='string'&&offline.canQueue(method,path,JSON.parse(init.body))){
        throw await offline.enqueue(method,path,init.body);
      }
    }
    throw new Error(typeof navigator!=='undefined'&&!navigator.onLine||offline&&!offline.getSyncState().online
      ?'No internet connection. This screen needs the internet (not saved offline). · අන්තර්ජාලය නැත.'
      :'Cannot reach the ERP server. Please try again.');
  }
  if(r.status===401&&path!=='/login'&&typeof window!=='undefined')window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
  if(!r.ok)throw new ApiError(r.data?.error||'Request failed',r.status);
  if(offline&&method==='GET')offline.cachePut(path,r.data).catch(()=>{});
  return r.data as T;
}
export const money=(cents:number)=>new Intl.NumberFormat('en-LK',{style:'currency',currency:'LKR',maximumFractionDigits:2}).format(cents/100);
export const idempotency=()=>crypto.randomUUID()+crypto.randomUUID();

export function normalizeWhatsApp(phone: string): string {
  if (!phone) return "";
  const digits = phone.replace(/\D/g, "");
  if (!digits) return "";
  if (digits.startsWith("0") && digits.length === 10) {
    return "94" + digits.slice(1);
  }
  if (digits.length === 9 && digits.startsWith("7")) {
    return "94" + digits;
  }
  if (digits.startsWith("94") && digits.length === 11) {
    return digits;
  }
  return digits;
}

export function formatWhatsApp(phone: string): string {
  const norm = normalizeWhatsApp(phone);
  if (norm.startsWith("94") && norm.length === 11) {
    return `+94 ${norm.slice(2, 4)} ${norm.slice(4, 7)} ${norm.slice(7)}`;
  }
  return phone;
}
