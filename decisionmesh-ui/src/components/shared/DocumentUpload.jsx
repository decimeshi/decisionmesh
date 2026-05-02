import { useState, useRef } from 'react';
import { Upload, FileText, X, Cloud, Code2, CheckCircle, Loader2, AlertCircle } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent, Button } from './index';
import { getUploadPresignedUrl } from '../../utils/api';

const ACCEPTED_TYPES = '.pdf,.png,.jpg,.jpeg,.tiff,.docx';
const MAX_BYTES      = 10 * 1024 * 1024; // 10 MB

// ── Helpers ────────────────────────────────────────────────────────────────────

function toBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload  = e  => resolve(e.target.result.split(',')[1]);
    reader.onerror = () => reject(new Error('Failed to read file'));
    reader.readAsDataURL(file);
  });
}

async function putToS3(presignedUrl, file) {
  const res = await fetch(presignedUrl, {
    method:  'PUT',
    headers: { 'Content-Type': file.type },
    body:    file,
  });
  if (!res.ok) throw new Error(`S3 upload failed — HTTP ${res.status}`);
}

function friendlySize(bytes) {
  return bytes < 1024 * 1024
    ? `${(bytes / 1024).toFixed(1)} KB`
    : `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

function docType(file) {
  if (file.type.includes('pdf'))  return 'PDF';
  if (file.type.includes('png'))  return 'PNG';
  if (file.type.includes('jpeg') || file.type.includes('jpg')) return 'JPEG';
  if (file.type.includes('tiff')) return 'TIFF';
  return file.type.split('/')[1]?.toUpperCase() ?? 'DOCUMENT';
}

// ── Method option ──────────────────────────────────────────────────────────────

function MethodCard({ id, icon, label, sub, selected, onSelect }) {
  return (
    <button type="button" onClick={() => onSelect(id)}
      className={`text-left p-3 rounded-lg border-2 transition-all w-full
        ${selected ? 'border-blue-500 bg-blue-50' : 'border-slate-200 hover:border-slate-300 bg-white'}`}>
      <div className={`flex items-center gap-1.5 mb-1 text-xs font-semibold
        ${selected ? 'text-blue-700' : 'text-slate-600'}`}>
        {icon}{label}
      </div>
      <p className="text-[10px] text-slate-400 leading-tight">{sub}</p>
    </button>
  );
}

// ── Main component ─────────────────────────────────────────────────────────────

/**
 * DocumentUpload
 *
 * Props:
 *   keycloak        – keycloak/auth instance (passed through to api.js for S3 presign)
 *   onDocumentReady – called with the input block to inject into the intent payload:
 *                     { documentType, fileName, documentBase64 }   ← base64 path
 *                     { documentType, fileName, documentUrl }       ← S3 path
 *   onClear         – called when the user removes the document
 */
export default function DocumentUpload({ keycloak, onDocumentReady, onClear }) {
  const [file,     setFile]     = useState(null);
  const [method,   setMethod]   = useState('base64');
  const [status,   setStatus]   = useState('idle');    // idle | processing | done | error
  const [progress, setProgress] = useState(0);
  const [docRef,   setDocRef]   = useState(null);
  const [errMsg,   setErrMsg]   = useState(null);
  const [dragging, setDragging] = useState(false);
  const inputRef = useRef();

  // ── File selection ────────────────────────────────────────────────────────

  function pickFile(f) {
    if (!f) return;
    if (f.size > MAX_BYTES) { setErrMsg(`File too large — max 10 MB`); return; }
    setFile(f);
    setErrMsg(null);
    setStatus('idle');
    setDocRef(null);
    setProgress(0);
  }

  function clear() {
    setFile(null);
    setStatus('idle');
    setDocRef(null);
    setErrMsg(null);
    setProgress(0);
    if (inputRef.current) inputRef.current.value = '';
    onClear?.();
  }

  // ── Processing ────────────────────────────────────────────────────────────

  async function process() {
    if (!file) return;
    setStatus('processing');
    setErrMsg(null);
    setProgress(15);

    try {
      let ref;

      if (method === 'base64') {
        const b64 = await toBase64(file);
        setProgress(90);
        ref = {
          documentType:   docType(file),
          fileName:        file.name,
          documentBase64:  b64,
        };
      } else {
        // S3 path — presign → PUT → store public URL
        setProgress(20);
        const presign = await getUploadPresignedUrl(keycloak, file.name, file.type);
        setProgress(45);
        await putToS3(presign.url, file);
        setProgress(90);
        ref = {
          documentType:  docType(file),
          fileName:       file.name,
          documentUrl:    presign.publicUrl,
        };
      }

      setProgress(100);
      setDocRef(ref);
      setStatus('done');
      onDocumentReady?.(ref);

    } catch (e) {
      setStatus('error');
      setErrMsg(e.message ?? 'Processing failed');
    }
  }

  // ── Render ────────────────────────────────────────────────────────────────

  const encodedKb = docRef?.documentBase64
    ? Math.ceil(docRef.documentBase64.length / 1024)
    : null;

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <CardTitle>Document input</CardTitle>
          {status === 'done' && (
            <span className="text-xs text-green-600 font-medium flex items-center gap-1">
              <CheckCircle size={11} /> Injected into payload
            </span>
          )}
        </div>
      </CardHeader>

      <CardContent className="space-y-3">

        {/* ── Drop zone (no file yet) ── */}
        {!file && (
          <div
            onDragOver={e  => { e.preventDefault(); setDragging(true);  }}
            onDragLeave={() => setDragging(false)}
            onDrop={e      => { e.preventDefault(); setDragging(false); pickFile(e.dataTransfer.files[0]); }}
            onClick={() => inputRef.current?.click()}
            className={`border-2 border-dashed rounded-xl p-6 text-center cursor-pointer transition-colors select-none
              ${dragging
                ? 'border-blue-400 bg-blue-50'
                : 'border-slate-200 hover:border-blue-300 hover:bg-slate-50'}`}
          >
            <input
              ref={inputRef} type="file" accept={ACCEPTED_TYPES}
              className="hidden" onChange={e => pickFile(e.target.files[0])}
            />
            <Upload size={22} className="mx-auto mb-2 text-slate-300" />
            <p className="text-sm font-medium text-slate-600">Drop file or click to browse</p>
            <p className="text-xs text-slate-400 mt-1">PDF · PNG · JPG · TIFF · DOCX — up to 10 MB</p>
          </div>
        )}

        {/* ── File chip ── */}
        {file && (
          <div className="flex items-center gap-3 p-3 bg-slate-50 rounded-lg border border-slate-200">
            <div className="w-8 h-8 rounded-lg bg-blue-600 flex items-center justify-center flex-shrink-0">
              <FileText size={14} className="text-white" />
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium text-slate-700 truncate">{file.name}</p>
              <p className="text-xs text-slate-400">{friendlySize(file.size)} · {docType(file)}</p>
            </div>
            {status !== 'processing' && (
              <button onClick={clear}
                className="p-1 rounded hover:bg-slate-200 text-slate-400 hover:text-red-500 transition-colors">
                <X size={14} />
              </button>
            )}
          </div>
        )}

        {/* ── Method selector (visible before processing) ── */}
        {file && status === 'idle' && (
          <div className="grid grid-cols-2 gap-2">
            <MethodCard
              id="base64" selected={method === 'base64'} onSelect={setMethod}
              icon={<Code2 size={13} />}
              label="Inline base64"
              sub="Embed in payload · best under 1 MB"
            />
            <MethodCard
              id="s3" selected={method === 's3'} onSelect={setMethod}
              icon={<Cloud size={13} />}
              label="S3 upload"
              sub="Pre-signed URL · best for large files"
            />
          </div>
        )}

        {/* ── Progress bar ── */}
        {status === 'processing' && (
          <div>
            <div className="flex items-center gap-2 mb-1.5">
              <Loader2 size={12} className="animate-spin text-blue-600" />
              <span className="text-xs text-slate-500">
                {method === 'base64' ? 'Encoding to base64…' : 'Uploading to S3…'}
              </span>
              <span className="text-xs text-slate-400 ml-auto">{progress}%</span>
            </div>
            <div className="h-1.5 bg-slate-100 rounded-full overflow-hidden">
              <div
                className="h-full bg-blue-500 rounded-full transition-all duration-300"
                style={{ width: `${progress}%` }}
              />
            </div>
          </div>
        )}

        {/* ── Success summary ── */}
        {status === 'done' && docRef && (
          <div className="p-3 bg-green-50 border border-green-200 rounded-lg space-y-1">
            <p className="text-xs font-medium text-green-700 flex items-center gap-1">
              <CheckCircle size={11} />
              {method === 'base64'
                ? `Base64 encoded — ${encodedKb} KB added to payload`
                : 'Uploaded to S3 — URL injected into payload'}
            </p>
            <p className="text-[10px] font-mono text-green-600 truncate">
              {docRef.documentUrl ?? `data:[${docType(file)}] base64 · ${encodedKb} KB`}
            </p>
          </div>
        )}

        {/* ── Error ── */}
        {errMsg && (
          <div className="flex items-start gap-2 p-3 bg-red-50 border border-red-200 rounded-lg">
            <AlertCircle size={13} className="text-red-500 mt-0.5 flex-shrink-0" />
            <p className="text-xs text-red-700">{errMsg}</p>
          </div>
        )}

        {/* ── Action buttons ── */}
        {file && status === 'idle' && (
          <Button className="w-full" size="sm" onClick={process}>
            <Upload size={13} />
            {method === 'base64' ? 'Convert & inject into payload' : 'Upload to S3 & inject URL'}
          </Button>
        )}

        {status === 'done' && (
          <button
            onClick={clear}
            className="w-full text-xs text-slate-400 hover:text-slate-600 transition-colors py-1 text-center">
            Remove document
          </button>
        )}

      </CardContent>
    </Card>
  );
}
