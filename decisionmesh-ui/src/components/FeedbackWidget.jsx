/**
 * FeedbackWidget.jsx — DecisionMesh Global Feedback Widget
 *
 * A floating feedback button rendered on every page via Page.jsx.
 * Submits authenticated feedback to POST /feedback on the backend.
 *
 * Payload shape:
 *   {
 *     rating:    1–5,
 *     category:  'bug' | 'feature' | 'general' | 'billing',
 *     comment:   string (optional),
 *     page:      window.location.pathname,
 *     userAgent: navigator.userAgent,
 *   }
 *
 * Integration:
 *   1. Import FeedbackWidget in Page.jsx
 *   2. Pass keycloak prop: <FeedbackWidget keycloak={keycloak} />
 *   3. Place it as the last child inside the Page wrapper div
 *
 * Backend table (add to your schema):
 *   CREATE TABLE user_feedback (
 *     id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 *     user_id     UUID REFERENCES users(id),
 *     rating      SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
 *     category    VARCHAR(20) NOT NULL,
 *     comment     TEXT,
 *     page        VARCHAR(255),
 *     user_agent  TEXT,
 *     created_at  TIMESTAMPTZ DEFAULT now()
 *   );
 */
import { useState, useEffect, useRef } from 'react';
import { MessageSquarePlus, X, Star, Send, Check, ChevronDown } from 'lucide-react';
import { request } from '../utils/api';

// ── Categories ────────────────────────────────────────────────────────────────
const CATEGORIES = [
  { id: 'bug',     label: '🐛 Bug report',      desc: 'Something isn\'t working'      },
  { id: 'feature', label: '✨ Feature request',  desc: 'Suggest an improvement'        },
  { id: 'billing', label: '💳 Billing',          desc: 'Question about plans or credits'},
  { id: 'general', label: '💬 General',          desc: 'Other feedback or thoughts'    },
];

// ── Star rating component ─────────────────────────────────────────────────────
function StarRating({ value, onChange }) {
  const [hovered, setHovered] = useState(0);

  const labels = ['', 'Poor', 'Fair', 'Good', 'Great', 'Excellent'];

  return (
    <div className="flex flex-col items-center gap-2">
      <div className="flex gap-1">
        {[1, 2, 3, 4, 5].map(star => (
          <button
            key={star}
            type="button"
            onClick={() => onChange(star)}
            onMouseEnter={() => setHovered(star)}
            onMouseLeave={() => setHovered(0)}
            className="p-1 transition-transform hover:scale-110 focus:outline-none"
          >
            <Star
              size={28}
              className="transition-colors duration-100"
              style={{
                fill:   (hovered || value) >= star ? '#f59e0b' : 'transparent',
                stroke: (hovered || value) >= star ? '#f59e0b' : '#cbd5e1',
              }}
            />
          </button>
        ))}
      </div>
      <span className="text-xs font-medium text-slate-500 h-4 transition-all">
        {labels[hovered || value] ?? ''}
      </span>
    </div>
  );
}

// ── Main widget ───────────────────────────────────────────────────────────────
export default function FeedbackWidget({ keycloak }) {
  const [open,       setOpen]       = useState(false);
  const [rating,     setRating]     = useState(0);
  const [category,   setCategory]   = useState('general');
  const [comment,    setComment]    = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [submitted,  setSubmitted]  = useState(false);
  const [error,      setError]      = useState(null);
  const [catOpen,    setCatOpen]    = useState(false);

  const modalRef  = useRef(null);
  const catRef    = useRef(null);
  const commentRef = useRef(null);

  // Close modal on Escape
  useEffect(() => {
    function onKey(e) {
      if (e.key === 'Escape') setOpen(false);
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  // Close category dropdown on outside click
  useEffect(() => {
    function onClickOutside(e) {
      if (catRef.current && !catRef.current.contains(e.target)) {
        setCatOpen(false);
      }
    }
    document.addEventListener('mousedown', onClickOutside);
    return () => document.removeEventListener('mousedown', onClickOutside);
  }, []);

  // Focus comment on open
  useEffect(() => {
    if (open && !submitted) {
      setTimeout(() => commentRef.current?.focus(), 150);
    }
  }, [open]);

  function handleOpen() {
    setOpen(true);
    setSubmitted(false);
    setError(null);
    setRating(0);
    setCategory('general');
    setComment('');
  }

  function handleClose() {
    setOpen(false);
    setCatOpen(false);
  }

  async function handleSubmit() {
    if (rating === 0) {
      setError('Please select a star rating before submitting.');
      return;
    }

    setSubmitting(true);
    setError(null);

    try {
      await request(keycloak, '/admin/feedback', {
        method: 'POST',
        body: JSON.stringify({
          rating,
          category,
          comment:   comment.trim(),
          page:      window.location.pathname,
          userAgent: navigator.userAgent,
        }),
      });
      setSubmitted(true);
      // Auto-close after 2.5s
      setTimeout(() => setOpen(false), 2500);
    } catch (e) {
      setError(e.message ?? 'Failed to submit feedback. Please try again.');
    } finally {
      setSubmitting(false);
    }
  }

  const selectedCat = CATEGORIES.find(c => c.id === category);

  return (
    <>
      {/* ── Floating trigger button ──────────────────────────────────────── */}
      <button
        onClick={handleOpen}
        aria-label="Share feedback"
        className={`
          fixed bottom-6 right-6 z-40
          flex items-center gap-2
          px-4 py-2.5
          bg-slate-900 text-white
          rounded-full shadow-lg shadow-slate-900/30
          text-xs font-semibold
          border border-slate-700
          hover:bg-slate-800 hover:shadow-xl hover:shadow-slate-900/40
          active:scale-95
          transition-all duration-200
          ${open ? 'opacity-0 pointer-events-none scale-90' : 'opacity-100 scale-100'}
        `}
      >
        <MessageSquarePlus size={14} />
        Feedback
      </button>

      {/* ── Backdrop ─────────────────────────────────────────────────────── */}
      {open && (
        <div
          className="fixed inset-0 z-40 bg-black/20 backdrop-blur-[2px]"
          onClick={handleClose}
        />
      )}

      {/* ── Modal ────────────────────────────────────────────────────────── */}
      <div
        ref={modalRef}
        className={`
          fixed bottom-6 right-6 z-50
          w-[340px]
          bg-white rounded-2xl shadow-2xl shadow-slate-900/20
          border border-slate-200
          overflow-hidden
          transition-all duration-300 ease-out
          ${open
            ? 'opacity-100 translate-y-0 scale-100 pointer-events-auto'
            : 'opacity-0 translate-y-4 scale-95 pointer-events-none'
          }
        `}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-slate-100 bg-slate-50">
          <div className="flex items-center gap-2">
            <div className="w-7 h-7 rounded-lg bg-slate-900 flex items-center justify-center">
              <MessageSquarePlus size={13} className="text-white" />
            </div>
            <div>
              <p className="text-sm font-bold text-slate-900 leading-none">Share feedback</p>
              <p className="text-[10px] text-slate-400 mt-0.5">Help us improve DecisionMesh</p>
            </div>
          </div>
          <button
            onClick={handleClose}
            className="p-1.5 rounded-lg text-slate-400 hover:text-slate-600 hover:bg-slate-200 transition-colors"
          >
            <X size={14} />
          </button>
        </div>

        {submitted ? (
          /* ── Success state ───────────────────────────────────────────── */
          <div className="flex flex-col items-center justify-center gap-3 py-10 px-5">
            <div className="w-12 h-12 rounded-full bg-emerald-100 flex items-center justify-center">
              <Check size={22} className="text-emerald-600" />
            </div>
            <div className="text-center">
              <p className="text-sm font-bold text-slate-900 mb-1">Thank you!</p>
              <p className="text-xs text-slate-500">
                Your feedback helps us build a better product.
              </p>
            </div>
          </div>
        ) : (
          /* ── Form ────────────────────────────────────────────────────── */
          <div className="p-5 space-y-4">

            {/* Star rating */}
            <div>
              <p className="text-[11px] font-semibold text-slate-500 uppercase tracking-wide mb-3">
                How's your experience?
              </p>
              <StarRating value={rating} onChange={setRating} />
            </div>

            {/* Category dropdown */}
            <div>
              <p className="text-[11px] font-semibold text-slate-500 uppercase tracking-wide mb-1.5">
                Category
              </p>
              <div ref={catRef} className="relative">
                <button
                  type="button"
                  onClick={() => setCatOpen(v => !v)}
                  className="w-full flex items-center justify-between px-3 py-2.5 rounded-xl border border-slate-200 bg-slate-50 hover:bg-white hover:border-slate-300 transition-colors text-left"
                >
                  <div>
                    <p className="text-xs font-medium text-slate-800">{selectedCat.label}</p>
                    <p className="text-[10px] text-slate-400">{selectedCat.desc}</p>
                  </div>
                  <ChevronDown
                    size={13}
                    className={`text-slate-400 transition-transform ${catOpen ? 'rotate-180' : ''}`}
                  />
                </button>

                {catOpen && (
                  <div className="absolute top-full left-0 right-0 mt-1 bg-white border border-slate-200 rounded-xl shadow-lg shadow-slate-200/60 z-10 overflow-hidden">
                    {CATEGORIES.map(cat => (
                      <button
                        key={cat.id}
                        type="button"
                        onClick={() => { setCategory(cat.id); setCatOpen(false); }}
                        className={`w-full flex items-start gap-3 px-3 py-2.5 text-left hover:bg-slate-50 transition-colors ${
                          category === cat.id ? 'bg-blue-50' : ''
                        }`}
                      >
                        <div className="flex-1">
                          <p className={`text-xs font-medium ${category === cat.id ? 'text-blue-700' : 'text-slate-800'}`}>
                            {cat.label}
                          </p>
                          <p className="text-[10px] text-slate-400">{cat.desc}</p>
                        </div>
                        {category === cat.id && (
                          <Check size={12} className="text-blue-600 mt-0.5 shrink-0" />
                        )}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            </div>

            {/* Comment */}
            <div>
              <p className="text-[11px] font-semibold text-slate-500 uppercase tracking-wide mb-1.5">
                Tell us more <span className="font-normal normal-case text-slate-400">(optional)</span>
              </p>
              <textarea
                ref={commentRef}
                value={comment}
                onChange={e => setComment(e.target.value)}
                placeholder="What's working well? What could be better?"
                maxLength={1000}
                rows={3}
                className="w-full text-xs text-slate-700 border border-slate-200 rounded-xl px-3 py-2.5 bg-slate-50 focus:outline-none focus:border-blue-400 focus:bg-white resize-none transition-colors placeholder:text-slate-300"
              />
              <p className="text-[10px] text-slate-300 text-right mt-0.5">
                {comment.length}/1000
              </p>
            </div>

            {/* Error */}
            {error && (
              <p className="text-[11px] text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
                {error}
              </p>
            )}

            {/* Submit */}
            <button
              onClick={handleSubmit}
              disabled={submitting}
              className="w-full flex items-center justify-center gap-2 py-2.5 rounded-xl bg-slate-900 text-white text-xs font-semibold hover:bg-slate-800 disabled:opacity-50 transition-all active:scale-[0.98]"
            >
              {submitting ? (
                <span className="w-3.5 h-3.5 border-2 border-white border-t-transparent rounded-full animate-spin" />
              ) : (
                <><Send size={12} /> Send feedback</>
              )}
            </button>

            <p className="text-[10px] text-slate-400 text-center">
              Feedback is linked to your account and reviewed by our team.
            </p>
          </div>
        )}
      </div>
    </>
  );
}
