// Web: the static export and API are served by the same Worker (same origin).
// Android: the bundled app calls the existing Worker at NEXT_PUBLIC_API_URL.
// On Android, Capacitor routes fetch() through the native HTTP stack, so the
// HttpOnly session cookie lives in Android's private cookie store and is never
// readable from JavaScript.
import { Capacitor, CapacitorHttp } from '@capacitor/core';

export const apiBase=process.env.NEXT_PUBLIC_API_URL?.replace(/\/$/,'')||'';

/** Fired when the server says the session is gone, so the UI can return to login. */
export const SESSION_EXPIRED_EVENT='erp-session-expired';

function nativeHeaders():Record<string,string>{
  if(typeof window==='undefined'||!Capacitor.isNativePlatform()||!apiBase)return {};
  // The Worker's CSRF guard requires an Origin header on writes. Browsers add it
  // automatically; native HTTP does not. The app identifies itself with the
  // ERP's own origin, exactly as the same-origin web app does. Authentication
  // (session cookie) and role permissions are still enforced by the server.
  // See ANDROID.md › "Backend items for approval" for the recommended
  // long-term server-side app origin allow-list.
  return {Origin:new URL(apiBase).origin,'X-ERP-Client':'android'};
}

type Result={ok:boolean;status:number;data:any};

// Android: call the native HTTP plugin directly. Capacitor's automatic fetch()
// patch builds a browser Request first, which silently drops the Origin
// header, so the Worker's CSRF check rejected every sign-in ("Invalid
// request origin").
async function nativeRequest(url:string,init:RequestInit):Promise<Result>{
  const headers:Record<string,string>={'Content-Type':'application/json',...nativeHeaders(),...(init.headers as Record<string,string>||{})};
  const res=await CapacitorHttp.request({url,method:(init.method||'GET').toUpperCase(),headers,data:typeof init.body==='string'?init.body:undefined,responseType:'json',connectTimeout:20000,readTimeout:30000});
  let data=res.data;
  if(typeof data==='string'){try{data=JSON.parse(data);}catch{data={error:data||'Unexpected response'};}}
  return {ok:res.status>=200&&res.status<300,status:res.status,data:data??{}};
}
async function webRequest(url:string,init:RequestInit):Promise<Result>{
  const response=await fetch(url,{...init,credentials:'include',headers:{'Content-Type':'application/json',...init.headers},cache:'no-store'});
  const data=await response.json().catch(()=>({error:'Unexpected response'}));
  return {ok:response.ok,status:response.status,data};
}

export async function api<T>(path:string,init:RequestInit={}):Promise<T>{
  const url=`${apiBase}/api${path}`;
  let r:Result;
  try{
    r=typeof window!=='undefined'&&Capacitor.isNativePlatform()&&apiBase?await nativeRequest(url,init):await webRequest(url,init);
  }catch{
    throw new Error(typeof navigator!=='undefined'&&!navigator.onLine?'No internet connection. Check your network and try again.':'Cannot reach the ERP server. Please try again.');
  }
  if(r.status===401&&path!=='/login'&&typeof window!=='undefined')window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
  if(!r.ok)throw new Error(r.data?.error||'Request failed');
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
