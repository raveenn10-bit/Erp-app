'use client';
import { isNative, ThermalPrinter, printPdf, type PrinterDevice } from '@/lib/native';
import { buildReceiptPdf, bytesToBase64, canvasToEscPos, renderReceiptCanvas } from './render';
import type { InvoiceModel } from './model';

export type PrinterSettings = {
  /** 'bluetooth' = direct ESC/POS to a paired printer; 'system' = Android print service. */
  mode: 'bluetooth' | 'system';
  paper: '58' | '80' | 'custom';
  customDots: number;
  customMm: number;
  device: PrinterDevice | null;
};
export const DEFAULT_PRINTER: PrinterSettings = { mode: 'bluetooth', paper: '58', customDots: 384, customMm: 58, device: null };
const KEY = 'thermal-printer';

export function paperSpec(s: PrinterSettings) {
  if (s.paper === '80') return { dots: 576, mm: 80 };
  if (s.paper === 'custom') return { dots: Math.max(200, Math.min(832, Math.round(s.customDots / 8) * 8)), mm: s.customMm || 58 };
  return { dots: 384, mm: 58 };
}

export async function loadPrinter(): Promise<PrinterSettings> {
  try {
    if (isNative()) {
      const { Preferences } = await import('@capacitor/preferences');
      const { value } = await Preferences.get({ key: KEY });
      return value ? { ...DEFAULT_PRINTER, ...JSON.parse(value) } : DEFAULT_PRINTER;
    }
    const v = localStorage.getItem(KEY);
    return v ? { ...DEFAULT_PRINTER, ...JSON.parse(v) } : DEFAULT_PRINTER;
  } catch { return DEFAULT_PRINTER; }
}
export async function savePrinter(s: PrinterSettings) {
  const value = JSON.stringify(s);
  if (isNative()) { const { Preferences } = await import('@capacitor/preferences'); await Preferences.set({ key: KEY, value }); }
  else { try { localStorage.setItem(KEY, value); } catch { /* storage unavailable */ } }
}

export async function listPairedPrinters(): Promise<PrinterDevice[]> {
  if (!isNative()) throw new Error('Bluetooth printing is available in the Android app only.');
  return (await ThermalPrinter.listPaired()).devices;
}

export async function printReceipt(m: InvoiceModel, s: PrinterSettings): Promise<string> {
  const { dots, mm } = paperSpec(s);
  if (s.mode === 'bluetooth' && isNative()) {
    if (!s.device) throw new Error('Choose a paired printer in Printer settings first.');
    const canvas = await renderReceiptCanvas(m, dots);
    await ThermalPrinter.print({ address: s.device.address, data: bytesToBase64(canvasToEscPos(canvas, dots)) });
    return `Sent to ${s.device.name}`;
  }
  await printPdf(await buildReceiptPdf(m, dots, mm), `${m.number}_Receipt.pdf`, false);
  return 'Opened print dialog';
}

/** Clearly labelled sample used only for "Test print" — never stored or sent anywhere. */
export function testReceiptModel(): InvoiceModel {
  return {
    number: 'TEST-PRINT', date: new Date().toISOString(), dueDate: null, status: 'test',
    business: { name: 'PRINTER TEST', address: 'Sanjeewa Distribution ERP', phone: '', whatsapp: '', email: '' },
    customer: { name: 'Test only – not an invoice', address: '', whatsapp: '' },
    paymentMethod: '', paymentTerms: '', notes: '', terms: '',
    lines: [{ no: 1, product: 'Alignment check', pack: 'සිංහල අකුරු පරීක්ෂාව', qty: 1, unit: 0, shopPrice: null, discount: 0, total: 0 }],
    showShopPrice: false, showCustomerWhatsapp: false,
    subtotal: 0, itemDiscount: 0, invoiceDiscount: 0, total: 0, paid: 0, outstanding: 0,
  };
}
