// Cloud imports run entirely in the browser: the user picks files with the provider's own picker, we download the
// bytes with the short-lived token the picker hands us, then upload them like local files. No cloud tokens reach
// our backend.

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID;
const GOOGLE_API_KEY = import.meta.env.VITE_GOOGLE_API_KEY;
const ONEDRIVE_CLIENT_ID = import.meta.env.VITE_ONEDRIVE_CLIENT_ID;

export const cloudConfigured = { google: !!(GOOGLE_CLIENT_ID && GOOGLE_API_KEY), onedrive: !!ONEDRIVE_CLIENT_ID };

const loaded = {};
function loadScript(src) {
  loaded[src] ??= new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = src; s.async = true; s.onload = resolve; s.onerror = () => reject(new Error(`Failed to load ${src}`));
    document.head.appendChild(s);
  });
  return loaded[src];
}

/** Google Drive via Google Picker + Identity Services. Scope drive.file = only files the user picks. */
export async function pickFromGoogleDrive(accept) {
  await Promise.all([loadScript('https://apis.google.com/js/api.js'), loadScript('https://accounts.google.com/gsi/client')]);
  await new Promise((r) => window.gapi.load('picker', r));
  const token = await new Promise((resolve, reject) => {
    window.google.accounts.oauth2.initTokenClient({
      client_id: GOOGLE_CLIENT_ID,
      scope: 'https://www.googleapis.com/auth/drive.file',
      callback: (res) => (res.error ? reject(new Error(res.error)) : resolve(res.access_token)),
    }).requestAccessToken();
  });
  const docs = await new Promise((resolve) => {
    const { picker } = window.google;
    new picker.PickerBuilder()
      .addView(new picker.DocsView(picker.ViewId.DOCS_IMAGES).setMimeTypes(accept))
      .enableFeature(picker.Feature.MULTISELECT_ENABLED)
      .setOAuthToken(token)
      .setDeveloperKey(GOOGLE_API_KEY)
      .setCallback((d) => {
        if (d.action === picker.Action.PICKED) resolve(d.docs);
        if (d.action === picker.Action.CANCEL) resolve([]);
      })
      .build().setVisible(true);
  });
  return Promise.all(docs.map(async (doc) => {
    const res = await fetch(`https://www.googleapis.com/drive/v3/files/${doc.id}?alt=media`, { headers: { Authorization: `Bearer ${token}` } });
    return new File([await res.blob()], doc.name, { type: doc.mimeType });
  }));
}

/** OneDrive File Picker v7.2 (personal + business); returns pre-authenticated download URLs. */
export async function pickFromOneDrive() {
  await loadScript('https://js.live.net/v7.2/OneDrive.js');
  const result = await new Promise((resolve, reject) => {
    window.OneDrive.open({
      clientId: ONEDRIVE_CLIENT_ID,
      action: 'download',
      multiSelect: true,
      advanced: { filter: '.png,.svg,.gif,.webp' },
      success: resolve,
      cancel: () => resolve({ value: [] }),
      error: reject,
    });
  });
  const mime = { png: 'image/png', svg: 'image/svg+xml', gif: 'image/gif', webp: 'image/webp' };
  return Promise.all(result.value.map(async (f) => {
    const res = await fetch(f['@microsoft.graph.downloadUrl']);
    const ext = f.name.split('.').pop().toLowerCase();
    return new File([await res.blob()], f.name, { type: mime[ext] ?? 'application/octet-stream' });
  }));
}
