/**
 * Billing.jsx — DecisionMesh Billing & Plans
 *
 * Gateway detection priority:
 *   1. Saved preference in localStorage (user override)
 *   2. JWT `locale` claim from Zitadel (hi, en-IN, ta, te, kn, ml → India)
 *   3. Timezone detection (Asia/Kolkata, Asia/Calcutta → India)
 *   4. Default: Stripe (international)
 *
 * Billing intervals: monthly | quarterly (−10%) | halfyearly (−15%) | yearly (−20%)
 *
 * Payment flows:
 *   Stripe   → redirect to Stripe Checkout hosted page
 *   Razorpay → JS popup (no redirect), verify signature on backend
 */
import { useState, useEffect, useCallback } from 'react';
import {
  CreditCard, Check, Zap, ArrowRight, Star, AlertCircle,
  ExternalLink, ShoppingCart, Globe, RefreshCw, Info,
  Key, Server, Eye, EyeOff, Plus, Trash2, Cpu, Link2,
  TrendingDown, Shield, Wifi,
} from 'lucide-react';
import { useSearchParams } from 'react-router-dom';
import Page from '../components/shared/Page';
import { Card, CardHeader, CardTitle, CardContent, Button, Spinner } from '../components/shared';
import { useCredits, MODEL_TIERS } from '../context/CreditContext';
import { formatDate } from '../lib/utils';
import { request } from '../utils/api';

// ── Constants from pricing doc ────────────────────────────────────────────────
const INDIA_LOCALES = ['hi', 'ta', 'te', 'kn', 'ml', 'mr', 'gu', 'pa', 'bn', 'en-IN', 'en-in'];
const INDIA_TIMEZONES = ['Asia/Kolkata', 'Asia/Calcutta'];
const GATEWAY_KEY = 'dm_payment_gateway';

// ── Billing intervals ─────────────────────────────────────────────────────────
const BILLING_INTERVALS = [
  { id: 'monthly',    label: 'Monthly',   months: 1,  discount: 0,    badge: null         },
  { id: 'quarterly',  label: 'Quarterly', months: 3,  discount: 0.10, badge: 'Save 10%'   },
  { id: 'halfyearly', label: '6 Months',  months: 6,  discount: 0.15, badge: 'Save 15%'   },
  { id: 'yearly',     label: 'Yearly',    months: 12, discount: 0.20, badge: 'Save 20%'   },
];

// ── Plans — matching PDF Section 4 ───────────────────────────────────────────
// priceId.stripe keys must match StripeService.priceMap entries in application.properties
// priceId.razorpay values are real Razorpay plan IDs (plan_xxx)
//   → Replace TODO entries with IDs returned from Razorpay plan creation curl commands
const PLANS = [
  {
    id: 'free', name: 'Free', color: '#64748b',
    usdPrice: { monthly: 0, quarterly: 0, halfyearly: 0, yearly: 0 },
    inrPrice: { monthly: 0, quarterly: 0, halfyearly: 0, yearly: 0 },
    credits: 500, creditsNote: '500 credits one-time',
    cta: 'Current free tier', priceId: null,
    features: ['500 credits (one-time gift)', '2 adapters', 'Budget enforcement',
               'Basic audit log (30 days)', 'Community support'],
  },
  {
    id: 'hobby', name: 'Hobby', color: '#475569',
    usdPrice: { monthly: 0, quarterly: 0, halfyearly: 0, yearly: 0 },
    inrPrice: { monthly: 0, quarterly: 0, halfyearly: 0, yearly: 0 },
    credits: 2000, creditsNote: '2k credits/mo',
    cta: 'Start Hobby',
    priceId: {
      stripe:   { monthly: 'hobby', quarterly: 'hobby', halfyearly: 'hobby', yearly: 'hobby' },
      razorpay: { monthly: 'plan_SdDy1dq630Eqr7', quarterly: 'plan_SdDy1dq630Eqr7',
                  halfyearly: 'plan_SdDy1dq630Eqr7', yearly: 'plan_SdDy1dq630Eqr7' },
    },
    features: ['2,000 credits/month', '3 adapters', 'Full audit log (90 days)', 'Email support'],
  },
  {
    id: 'builder', name: 'Builder', color: '#2563eb', popular: true,
    // USD: $19/mo · Quarterly $51 (−10%) · Half-yearly $97 (−15%) · Yearly $182 (−20%)
    usdPrice: { monthly: 19, quarterly: 51,   halfyearly: 97,   yearly: 182  },
    // INR: ₹1,599/mo · Quarterly ₹4,317 · Half-yearly ₹8,154 · Yearly ₹15,349
    inrPrice: { monthly: 1599, quarterly: 4317, halfyearly: 8154, yearly: 15349 },
    credits: 15000, creditsNote: '15k credits/mo',
    cta: 'Upgrade to Builder',
    priceId: {
      stripe: {
        monthly:    'builder',
        quarterly:  'builder_quarterly',
        halfyearly: 'builder_halfyearly',
        yearly:     'builder_yearly',
      },
      razorpay: {
        monthly:    'plan_SdDtQreYZOuDuZ',
        quarterly:  null,   // contact sales or configure in Razorpay dashboard
        halfyearly: null,  // contact sales or configure in Razorpay dashboard
        yearly:     null,       // contact sales or configure in Razorpay dashboard
      },
    },
    features: ['15,000 credits/month', 'All adapters', 'Policy builder',
               'Decision replay', 'Full audit + CSV export', 'Drift detection',
               'Priority support', 'Overage: $0.002/cr'],
    badge: 'Most popular',
  },
  {
    id: 'pro', name: 'Pro', color: '#4f46e5',
    // USD: $49/mo · Quarterly $132 (−10%) · Half-yearly $250 (−15%) · Yearly $470 (−20%)
    usdPrice: { monthly: 49, quarterly: 132, halfyearly: 250, yearly: 470  },
    // INR: ₹4,099/mo · Quarterly ₹11,067 · Half-yearly ₹20,904 · Yearly ₹39,350
    inrPrice: { monthly: 4099, quarterly: 11067, halfyearly: 20904, yearly: 39350 },
    credits: 60000, creditsNote: '60k credits/mo',
    cta: 'Upgrade to Pro',
    priceId: {
      stripe: {
        monthly:    'pro',
        quarterly:  'pro_quarterly',
        halfyearly: 'pro_halfyearly',
        yearly:     'pro_yearly',
      },
      razorpay: {
        monthly:    'plan_SdDv8HzOQxPoFm',
        quarterly:  null,   // contact sales or configure in Razorpay dashboard
        halfyearly: null,  // contact sales or configure in Razorpay dashboard
        yearly:     null,       // contact sales or configure in Razorpay dashboard
      },
    },
    features: ['60,000 credits/month', 'Multi-tenancy', '5 team seats',
               'SSO / SAML', 'Human-in-the-loop gates', 'Priority support',
               'BYOK — bring your own API key (1 cr/exec)',
               'Overage: $0.001/cr'],
  },
  {
    id: 'enterprise', name: 'Enterprise', color: '#7c3aed',
    usdPrice: null, inrPrice: null,
    credits: null, creditsNote: 'Unlimited',
    cta: 'Contact sales', priceId: null,
    features: ['Unlimited credits', 'PII detection & masking',
               'Model version tracking', 'Immutable signed audit log',
               'GDPR data residency', 'HIPAA / PCI-DSS templates',
               'BYOK — bring your own API key (1 cr/exec)',
               'BYOM — bring your own model, zero data egress',
               'Dedicated SLA'],
  },
];

// ── Credit packs — matching PDF Section 8 ────────────────────────────────────
const CREDIT_PACKS = [
  {
    id: 'starter', name: 'Starter', credits: 12000,
    usdPrice: 10, inrPrice: 849,
    usdPerCr: '$0.00083', inrPerCr: '₹0.0707',
    // stripe: backend resolves from stripe.price.credits.starter
    // razorpay: backend resolves amount from razorpay.credits.starter.amount (84900 paise)
    priceId: { stripe: 'starter', razorpay: 'credits_starter' },
  },
  {
    id: 'growth', name: 'Growth', credits: 32000,
    usdPrice: 25, inrPrice: 2099,
    usdPerCr: '$0.00078', inrPerCr: '₹0.0656',
    popular: true,
    priceId: { stripe: 'growth', razorpay: 'credits_growth' },
  },
  {
    id: 'scale', name: 'Scale', credits: 100000,
    usdPrice: 75, inrPrice: 6299,
    usdPerCr: '$0.00075', inrPerCr: '₹0.0630',
    priceId: { stripe: 'scale', razorpay: 'credits_scale' },
  },
];

// ── BYOK providers ────────────────────────────────────────────────────────────
const BYOK_PROVIDERS = [
  {
    id:          'anthropic',
    name:        'Anthropic',
    logo:        '🤖',
    keyPrefix:   'sk-ant-',
    placeholder: 'sk-ant-api03-...',
    models:      'Claude Haiku · Claude Sonnet · Claude Opus',
    docsUrl:     'https://console.anthropic.com/settings/keys',
    color:       '#D97706',
    bg:          '#FFFBEB',
  },
  {
    id:          'openai',
    name:        'OpenAI',
    logo:        '⚡',
    keyPrefix:   'sk-',
    placeholder: 'sk-proj-...',
    models:      'GPT-4o-mini · GPT-4o · GPT-4 Turbo',
    docsUrl:     'https://platform.openai.com/api-keys',
    color:       '#16A34A',
    bg:          '#F0FDF4',
  },
  {
    id:          'cohere',
    name:        'Cohere',
    logo:        '🌊',
    keyPrefix:   '',
    placeholder: 'Enter your Cohere API key',
    models:      'Command R · Command R+',
    docsUrl:     'https://dashboard.cohere.com/api-keys',
    color:       '#0D9488',
    bg:          '#F0FDFA',
  },
  {
    id:          'azure',
    name:        'Azure OpenAI',
    logo:        '☁️',
    keyPrefix:   '',
    placeholder: 'Azure OpenAI API key',
    models:      'GPT-4o · GPT-4 Turbo (Azure-hosted)',
    docsUrl:     'https://portal.azure.com',
    color:       '#2563EB',
    bg:          '#EFF6FF',
  },
];

// ── BYOM model types ──────────────────────────────────────────────────────────
const BYOM_TYPES = [
  { id: 'layoutlmv3',   label: 'LayoutLMv3',          sub: 'Document classification + NER',     color: '#7C3AED' },
  { id: 'ollama',       label: 'Ollama (self-hosted)', sub: 'Llama · Mistral · Phi3',            color: '#0D9488' },
  { id: 'huggingface',  label: 'HuggingFace endpoint', sub: 'Any HF Inference Endpoint',        color: '#F59E0B' },
  { id: 'custom',       label: 'Custom REST endpoint', sub: 'Your own model API',               color: '#64748B' },
];
function detectGateway(keycloak) {
  // 1. User saved preference
  const saved = localStorage.getItem(GATEWAY_KEY);
  if (saved === 'stripe' || saved === 'razorpay') return saved;

  // 2. JWT locale claim from Zitadel
  const locale = keycloak?.tokenParsed?.locale ?? '';
  if (locale && INDIA_LOCALES.some(l => locale.toLowerCase().startsWith(l.toLowerCase()))) {
    return 'razorpay';
  }

  // 3. Email domain heuristic (.in TLD)
  const email = keycloak?.tokenParsed?.email ?? '';
  if (email.endsWith('.in')) return 'razorpay';

  // 4. Timezone detection
  try {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (INDIA_TIMEZONES.includes(tz)) return 'razorpay';
  } catch {}

  // 5. Browser locale
  try {
    const navLocale = navigator.language ?? '';
    if (INDIA_LOCALES.some(l => navLocale.toLowerCase().startsWith(l.toLowerCase()))) {
      return 'razorpay';
    }
  } catch {}

  return 'stripe';
}

function saveGateway(gw) {
  localStorage.setItem(GATEWAY_KEY, gw);
}

// ── Razorpay loader ───────────────────────────────────────────────────────────
async function loadRazorpay() {
  if (window.Razorpay) return true;
  return new Promise(resolve => {
    const s = document.createElement('script');
    s.src = 'https://checkout.razorpay.com/v1/checkout.js';
    s.onload  = () => resolve(true);
    s.onerror = () => resolve(false);
    document.head.appendChild(s);
  });
}

async function openRazorpay(order, userEmail) {
  const loaded = await loadRazorpay();
  if (!loaded) throw new Error('Razorpay SDK failed to load');

  return new Promise((resolve, reject) => {
    // Subscriptions use subscription_id; one-time payments use order_id + amount
    const checkoutParams = order.subscriptionId
      ? { subscription_id: order.subscriptionId }
      : { order_id: order.orderId, amount: order.amount, currency: order.currency || 'INR' };

    const rzp = new window.Razorpay({
      key:         order.keyId,
      name:        'DecisionMesh',
      description: order.mode === 'payment' ? 'Credit Pack' : 'Subscription Plan',
      image:       '/decimeshi-icon.svg',
      prefill:     { email: userEmail },
      theme:       { color: '#2563eb' },
      ...checkoutParams,
      handler:     (r) => resolve({
        orderId:   r.razorpay_order_id,
        paymentId: r.razorpay_payment_id,
        signature: r.razorpay_signature,
      }),
      modal: { ondismiss: () => reject(new Error('cancelled')) },
    });
    rzp.open();
  });
}

// ── Price display helpers ─────────────────────────────────────────────────────
function formatPrice(plan, gateway, interval = 'monthly') {
  const isInr   = gateway === 'razorpay';
  const prices  = isInr ? plan.inrPrice : plan.usdPrice;
  const sym     = isInr ? '₹' : '$';

  if (prices === null) return { main: 'Custom', sub: null, monthly: null, savings: null };

  const total = typeof prices === 'object' ? prices[interval] : prices;
  if (total === 0) return { main: 'Free', sub: null, monthly: null, savings: null };

  const iv = BILLING_INTERVALS.find(i => i.id === interval);
  const months = iv?.months ?? 1;

  // Effective per-month for multi-month intervals
  const perMonth = months > 1 ? (total / months) : null;
  const monthlyFull = typeof prices === 'object' ? prices.monthly : prices;
  const savings = months > 1 ? (monthlyFull * months - total) : null;

  return {
    main:    `${sym}${total.toLocaleString()}`,
    sub:     months === 1 ? '/mo' : `/ ${months} mo`,
    monthly: perMonth ? `${sym}${(perMonth).toFixed(0)}/mo` : null,
    savings: savings  ? `Save ${sym}${savings.toLocaleString()}` : null,
  };
}

// ── Plan card ─────────────────────────────────────────────────────────────────
function PlanCard({ plan, currentPlanId, onSelect, selecting, gateway, interval }) {
  const isCurrent = plan.id === currentPlanId;
  const { main, sub, monthly, savings } = formatPrice(plan, gateway, interval);
  const col = plan.color;

  return (
    <div className={`relative flex flex-col rounded-2xl border-2 p-5 transition-all h-full ${
      plan.popular   ? 'border-blue-500 shadow-lg shadow-blue-100'
      : isCurrent    ? 'border-slate-300 bg-slate-50'
                     : 'border-slate-200 hover:border-slate-300'
    }`}>
      {plan.popular && (
        <div className="absolute -top-3 left-1/2 -translate-x-1/2 whitespace-nowrap">
          <span className="flex items-center gap-1 bg-blue-600 text-white text-[10px] font-bold px-3 py-1 rounded-full">
            <Star size={9} fill="white" /> Most popular
          </span>
        </div>
      )}
      {isCurrent && (
        <span className="absolute top-3 right-3 text-[10px] font-medium text-slate-400 bg-slate-100 px-2 py-0.5 rounded-full">
          Current
        </span>
      )}

      {/* Header */}
      <div className="mb-3">
        <div className="flex items-center gap-1.5 mb-2">
          <span className="w-2 h-2 rounded-full shrink-0" style={{ backgroundColor: col }} />
          <h3 className="text-sm font-bold text-slate-900">{plan.name}</h3>
        </div>
        <div className="flex items-end gap-0.5 mb-0.5">
          <span className="text-2xl font-extrabold text-slate-900">{main}</span>
          {sub && <span className="text-slate-400 text-xs mb-0.5 ml-0.5">{sub}</span>}
        </div>
        {/* Effective monthly rate for multi-month intervals */}
        {monthly && (
          <p className="text-[11px] text-slate-400 mb-0.5">≈ {monthly} effective</p>
        )}
        {/* Savings badge */}
        {savings && (
          <span className="inline-block text-[10px] font-bold text-emerald-700 bg-emerald-50 border border-emerald-200 px-1.5 py-0.5 rounded-full mb-1">
            {savings}
          </span>
        )}
        <p className="text-xs font-semibold" style={{ color: col }}>{plan.creditsNote}</p>
      </div>

      {/* Features */}
      <ul className="flex-1 space-y-1.5 mb-4">
        {plan.features.map(f => (
          <li key={f} className="flex items-start gap-1.5 text-xs text-slate-600">
            <Check size={11} className="shrink-0 mt-0.5" style={{ color: col }} />
            {f}
          </li>
        ))}
      </ul>

      {/* CTA */}
      {plan.id === 'enterprise' ? (
        <a href="mailto:sales@decisionmesh.io"
          className="flex items-center justify-center gap-1.5 py-2.5 px-4 rounded-xl text-xs font-semibold border-2 transition-colors hover:opacity-80"
          style={{ borderColor: col, color: col }}>
          Contact sales <ArrowRight size={11} />
        </a>
      ) : isCurrent ? (
        <div className="flex items-center justify-center gap-1.5 py-2.5 rounded-xl text-xs font-semibold bg-slate-100 text-slate-400">
          <Check size={11} /> Current plan
        </div>
      ) : !plan.priceId ? (
        <div className="py-2.5 rounded-xl text-xs font-semibold bg-slate-50 text-slate-400 text-center">
          {plan.cta}
        </div>
      ) : (
        <button
          onClick={() => onSelect(plan)}
          disabled={!!selecting}
          className="flex items-center justify-center gap-1.5 py-2.5 rounded-xl text-xs font-semibold text-white transition-opacity disabled:opacity-60"
          style={{ backgroundColor: col }}
        >
          {selecting === plan.id ? <Spinner className="w-4 h-4" /> : <>{plan.cta} <ArrowRight size={11} /></>}
        </button>
      )}
    </div>
  );
}

// ── Credit pack card ──────────────────────────────────────────────────────────
function PackCard({ pack, onBuy, selecting, gateway }) {
  const isInr    = gateway === 'razorpay';
  const price    = isInr ? pack.inrPrice : pack.usdPrice;
  const sym      = isInr ? '₹' : '$';
  const perCr    = isInr ? pack.inrPerCr : pack.usdPerCr;
  const isLoading = selecting === pack.id;

  return (
    <div className={`relative rounded-2xl border-2 p-5 transition-all ${
      pack.popular ? 'border-blue-500 shadow-md shadow-blue-100' : 'border-slate-200 hover:border-slate-300'
    }`}>
      {pack.popular && (
        <div className="absolute -top-3 left-1/2 -translate-x-1/2 whitespace-nowrap">
          <span className="bg-blue-600 text-white text-[10px] font-bold px-3 py-1 rounded-full">Best value</span>
        </div>
      )}

      <div className="flex items-start justify-between mb-3">
        <div>
          <p className="text-sm font-bold text-slate-900">{pack.name}</p>
          <p className="text-xs text-slate-500">{pack.credits.toLocaleString()} credits</p>
          <p className="text-[10px] text-slate-400 mt-0.5">{perCr}/cr</p>
        </div>
        <div className="text-right">
          <p className="text-2xl font-extrabold text-slate-900">{sym}{price.toLocaleString()}</p>
          <p className="text-[10px] text-slate-400">one-time</p>
        </div>
      </div>

      {/* Intent estimates */}
      <div className="flex gap-2 mb-4">
        {Object.entries(MODEL_TIERS).map(([k, t]) => (
          <div key={k} className="flex-1 rounded-lg p-2 text-center text-[10px]"
            style={{ backgroundColor: t.bg, color: t.color }}>
            <div className="font-bold">~{Math.floor(pack.credits / t.credits).toLocaleString()}</div>
            <div className="opacity-70">{t.label}</div>
          </div>
        ))}
      </div>

      <button
        onClick={() => onBuy(pack)}
        disabled={!!selecting}
        className="w-full flex items-center justify-center gap-1.5 py-2.5 rounded-xl text-xs font-semibold bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-60 transition-colors"
      >
        {isLoading ? <Spinner className="w-4 h-4" /> : <><ShoppingCart size={12} /> Buy {pack.name}</>}
      </button>
    </div>
  );
}

// ── Usage bar ─────────────────────────────────────────────────────────────────
function UsageBar({ label, used = 0, limit = 0, color = '#2563eb' }) {
  const pct = limit ? Math.min(100, (used / limit) * 100) : 0;
  const barColor = pct > 85 ? '#dc2626' : pct > 60 ? '#d97706' : color;
  return (
    <div>
      <div className="flex justify-between text-xs mb-1">
        <span className="text-slate-600 font-medium">{label}</span>
        <span className="text-slate-500">{used.toLocaleString()} / {limit.toLocaleString()}</span>
      </div>
      <div className="h-1.5 bg-slate-100 rounded-full overflow-hidden">
        <div className="h-full rounded-full transition-all" style={{ width: `${pct}%`, backgroundColor: barColor }} />
      </div>
    </div>
  );
}

// ── Gateway badge ─────────────────────────────────────────────────────────────
function GatewayToggle({ gateway, onSwitch, autoDetected }) {
  return (
    <div className="flex flex-wrap items-center gap-3">
      <div className="flex items-center gap-1.5 text-xs text-slate-500">
        <Globe size={13} />
        <span>Payment via:</span>
        {autoDetected && (
          <span className="bg-green-50 text-green-700 border border-green-200 text-[10px] px-1.5 py-0.5 rounded-full">
            auto-detected
          </span>
        )}
      </div>

      <div className="flex rounded-lg border border-slate-200 overflow-hidden text-xs">
        {[
          { id: 'stripe',   flag: '💳', label: 'International (USD)', sub: 'Cards · PayPal' },
          { id: 'razorpay', flag: '🇮🇳', label: 'India (INR / UPI)',  sub: 'UPI · Cards · Netbanking' },
        ].map(g => (
          <button key={g.id}
            onClick={() => onSwitch(g.id)}
            title={g.sub}
            className={`flex items-center gap-1.5 px-3 py-2 font-medium transition-colors ${
              gateway === g.id ? 'bg-blue-600 text-white' : 'bg-white text-slate-600 hover:bg-slate-50'
            }`}>
            <span>{g.flag}</span>
            <span>{g.label}</span>
          </button>
        ))}
      </div>

      {gateway === 'razorpay' && (
        <span className="text-xs text-emerald-600 font-medium">
          ✓ UPI • Debit/Credit Cards • Netbanking • Wallets
        </span>
      )}
    </div>
  );
}

// ── Main component ────────────────────────────────────────────────────────────
export default function Billing({ keycloak }) {
  const [searchParams]                  = useSearchParams();
  const { balance, allocated, reload }  = useCredits();

  const [subscription, setSubscription] = useState(null);
  const [usage,        setUsage]        = useState(null);
  const [dataLoading,  setDataLoading]  = useState(true);
  const [selecting,    setSelecting]    = useState(null);  // plan/pack id being processed
  const [tab,          setTab]          = useState(searchParams.get('tab') === 'credits' ? 'credits' : searchParams.get('tab') === 'byok' ? 'byok' : 'plans');
  const [interval,     setInterval]     = useState('monthly');
  const [gateway,      setGateway]      = useState(() => detectGateway(keycloak));
  const [autoDetected, setAutoDetected] = useState(() => !localStorage.getItem(GATEWAY_KEY));
  const [toast,        setToast]        = useState(null);

  // BYOK state
  const [byokKeys,     setByokKeys]     = useState({});   // { anthropic: 'sk-ant-...', openai: 'sk-...' }
  const [byokVisible,  setByokVisible]  = useState({});   // { anthropic: true }
  const [byokSaving,   setByokSaving]   = useState(null);
  const [byokSaved,    setByokSaved]    = useState({});   // { anthropic: true } — confirmed saved

  // BYOM state
  const [byomEndpoints, setByomEndpoints] = useState([]);  // [{ id, name, url, type, authHeader, active }]
  const [byomForm,      setByomForm]      = useState({ name: '', url: '', type: 'ollama', authHeader: '' });
  const [byomAdding,    setByomAdding]    = useState(false);
  const [byomSaving,    setByomSaving]    = useState(false);

  const userEmail = keycloak?.tokenParsed?.email ?? '';
  const currentPlan = subscription?.plan ?? 'free';

  // Re-run gateway detection once the Zitadel JWT is available.
  // On first render keycloak.tokenParsed may still be null (token not yet
  // parsed), so locale / email signals would be missed. This effect fires
  // whenever the token changes and updates the gateway only if the user has
  // not already set a manual preference.
  useEffect(() => {
    if (localStorage.getItem(GATEWAY_KEY)) return; // respect saved preference
    const detected = detectGateway(keycloak);
    setGateway(detected);
    setAutoDetected(true);
  }, [keycloak?.tokenParsed]);

  // ── Load subscription + usage ─────────────────────────────────────────────
  useEffect(() => {
    Promise.allSettled([
      request(keycloak, '/billing/subscription'),
      request(keycloak, '/billing/usage'),
    ]).then(([sub, use]) => {
      if (sub.value) setSubscription(sub.value);
      if (use.value) setUsage(use.value);
    }).finally(() => setDataLoading(false));
  }, []);

  // ── Handle return from Stripe ─────────────────────────────────────────────
  useEffect(() => {
    if (searchParams.get('success') === '1') {
      const credits = searchParams.get('credits');
      showToast('success', credits
        ? `${parseInt(credits).toLocaleString()} credits added to your account!`
        : 'Plan activated! All features are now available.');
      reload();
    }
    if (searchParams.get('cancelled') === '1') {
      showToast('info', 'Checkout cancelled — no charges were made.');
    }
  }, []);

  function showToast(type, text) {
    setToast({ type, text });
    setTimeout(() => setToast(null), 6000);
  }

  function handleGatewaySwitch(gw) {
    setGateway(gw);
    setAutoDetected(false);
    saveGateway(gw); // persist so detectGateway respects it on next load
  }

  // ── Stripe plan / pack ────────────────────────────────────────────────────
  // planId = plan key e.g. "builder" | "pro" | "starter" | "growth" | "scale"
  // Backend resolves the real Stripe price_xxx ID from application.properties.
  async function stripeCheckout(planId, mode, extraBody = {}) {
    if (!userEmail) {
      showToast('error', 'Could not read your email from session — please log out and back in.');
      return;
    }
    const res = await request(keycloak, '/billing/checkout', {
      method: 'POST',
      body: JSON.stringify({
        email: userEmail,
        plan:  planId,
        mode,
        ...extraBody,
      }),
    });
    if (res?.checkoutUrl) {
      window.location.href = res.checkoutUrl;
    } else {
      throw new Error('No checkout URL returned from server');
    }
  }

  // ── Razorpay plan / pack ──────────────────────────────────────────────────
  async function razorpayCheckout(priceId, mode, extraBody = {}) {
    // Step 1: create order on backend
    const order = await request(keycloak, '/billing/razorpay/order', {
      method: 'POST',
      body: JSON.stringify({ priceId, mode, ...extraBody }),
    });
    if (!order?.orderId) throw new Error('Failed to create Razorpay order');

    // Step 2: open Razorpay popup
    const payment = await openRazorpay(order, userEmail);

    // Step 3: verify signature on backend
    const verify = await request(keycloak, '/billing/razorpay/verify', {
      method: 'POST',
      body: JSON.stringify({ ...payment, priceId, mode, ...extraBody }),
    });

    if (!verify?.success) throw new Error('Payment verification failed');
    return verify;
  }

  // ── Select plan ───────────────────────────────────────────────────────────
  async function handleSelectPlan(plan) {
    if (!plan.priceId) return;
    setSelecting(plan.id);
    try {
      if (gateway === 'stripe') {
        // Resolve interval-aware key: e.g. "builder_quarterly"
        // Backend maps this to the real Stripe price_xxx from application.properties
        const stripeKey = plan.priceId.stripe[interval];
        await stripeCheckout(stripeKey, 'subscription', { plan: plan.id, interval });
      } else {
        // Resolve real Razorpay plan_xxx ID for this interval
        const rzpPlanId = plan.priceId.razorpay[interval];
        if (!rzpPlanId) {
          showToast('info', `${plan.name} ${interval} billing is not yet configured. Please contact sales or use monthly billing.`);
          setSelecting(null);
          return;
        }
        await razorpayCheckout(rzpPlanId, 'subscription', { plan: plan.id, interval });
        showToast('success', `${plan.name} plan activated! Credits will be added shortly.`);
        await reload();
      }
    } catch (e) {
      if (e.message !== 'cancelled')
        showToast('error', e.message ?? 'Payment failed — please try again');
    } finally {
      setSelecting(null);
    }
  }

  // ── Buy credit pack ───────────────────────────────────────────────────────
  async function handleBuyPack(pack) {
    setSelecting(pack.id);
    try {
      if (gateway === 'stripe') {
        // pack.id = "starter" | "growth" | "scale" — backend maps to credits_xxx price
        await stripeCheckout(pack.id, 'payment', { creditAmount: pack.credits });
      } else {
        const pid = pack.priceId[gateway];
        await razorpayCheckout(pid, 'payment', { creditAmount: pack.credits });
        showToast('success', `${pack.credits.toLocaleString()} credits added to your account!`);
        await reload();
      }
    } catch (e) {
      if (e.message !== 'cancelled')
        showToast('error', e.message ?? 'Purchase failed — please try again');
    } finally {
      setSelecting(null);
    }
  }

  // ── BYOK handlers ─────────────────────────────────────────────────────────
  async function handleSaveByokKey(providerId) {
    const key = byokKeys[providerId];
    if (!key?.trim()) return;
    setByokSaving(providerId);
    try {
      await request(keycloak, '/byok/keys', {
        method: 'POST',
        body:   JSON.stringify({ provider: providerId, apiKey: key.trim() }),
      });
      setByokSaved(s => ({ ...s, [providerId]: true }));
      showToast('success', `${BYOK_PROVIDERS.find(p => p.id === providerId)?.name} key saved — 1 credit per execution.`);
    } catch (e) {
      showToast('error', e.message ?? 'Failed to save key');
    } finally {
      setByokSaving(null);
    }
  }

  async function handleDeleteByokKey(providerId) {
    try {
      await request(keycloak, `/byok/keys/${providerId}`, { method: 'DELETE' });
      setByokKeys(k  => { const n = { ...k };  delete n[providerId]; return n; });
      setByokSaved(s => { const n = { ...s };  delete n[providerId]; return n; });
      showToast('success', 'Key removed.');
    } catch (e) {
      showToast('error', e.message ?? 'Failed to remove key');
    }
  }

  // ── BYOM handlers ──────────────────────────────────────────────────────────
  async function handleAddByomEndpoint() {
    if (!byomForm.name.trim() || !byomForm.url.trim()) return;
    setByomSaving(true);
    try {
      const saved = await request(keycloak, '/byom/endpoints', {
        method: 'POST',
        body:   JSON.stringify(byomForm),
      });
      setByomEndpoints(e => [...e, saved ?? { ...byomForm, id: Date.now().toString(), active: true }]);
      setByomForm({ name: '', url: '', type: 'ollama', authHeader: '' });
      setByomAdding(false);
      showToast('success', 'Model endpoint registered — 1 credit per execution.');
    } catch (e) {
      showToast('error', e.message ?? 'Failed to register endpoint');
    } finally {
      setByomSaving(false);
    }
  }

  async function handleDeleteByomEndpoint(id) {
    try {
      await request(keycloak, `/byom/endpoints/${id}`, { method: 'DELETE' });
      setByomEndpoints(e => e.filter(ep => ep.id !== id));
      showToast('success', 'Endpoint removed.');
    } catch (e) {
      showToast('error', e.message ?? 'Failed to remove endpoint');
    }
  }

  const creditsUsed = (allocated ?? 500) - (balance ?? 0);

  return (
    <Page title="Billing & plans" subtitle="Manage your subscription, credits and payment method">

      {/* Toast */}
      {toast && (
        <div className={`flex items-start gap-3 px-4 py-3 rounded-xl text-sm font-medium border ${
          toast.type === 'success' ? 'bg-emerald-50 border-emerald-200 text-emerald-800'
          : toast.type === 'error' ? 'bg-red-50 border-red-200 text-red-800'
          : 'bg-blue-50 border-blue-200 text-blue-800'
        }`}>
          {toast.type === 'success' ? <Check size={15} className="shrink-0 mt-0.5" />
           : toast.type === 'error' ? <AlertCircle size={15} className="shrink-0 mt-0.5" />
           : <Info size={15} className="shrink-0 mt-0.5" />}
          <span className="flex-1">{toast.text}</span>
          <button onClick={() => setToast(null)} className="opacity-50 hover:opacity-100">✕</button>
        </div>
      )}

      {dataLoading ? (
        <div className="flex justify-center py-20"><Spinner className="w-8 h-8" /></div>
      ) : (
        <>
          {/* Balance + usage summary */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <Card className="p-5">
              <p className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-3">Credit balance</p>
              <div className="flex items-end gap-2 mb-2">
                <span className="text-4xl font-extrabold"
                  style={{ color: balance <= 0 ? '#dc2626' : balance < 100 ? '#d97706' : '#16a34a' }}>
                  {balance?.toLocaleString() ?? '—'}
                </span>
                <span className="text-slate-400 text-sm mb-1">/ {allocated?.toLocaleString()}</span>
              </div>
              <div className="h-1.5 bg-slate-100 rounded-full overflow-hidden mb-3">
                <div className="h-full rounded-full transition-all"
                  style={{
                    width: `${allocated ? Math.min(100, (balance / allocated) * 100) : 0}%`,
                    backgroundColor: balance <= 0 ? '#dc2626' : balance < 100 ? '#d97706' : '#16a34a',
                  }} />
              </div>
              <div className="flex items-center gap-2 text-xs text-slate-500">
                <span>Plan:</span>
                <span className="font-semibold capitalize text-slate-700 bg-slate-100 px-2 py-0.5 rounded-full">
                  {currentPlan}
                </span>
                {subscription?.nextBilling && (
                  <span>· Renews {subscription.nextBilling}</span>
                )}
              </div>
            </Card>

            <Card className="md:col-span-2 p-5">
              <div className="flex items-center justify-between mb-4">
                <p className="text-xs font-semibold text-slate-500 uppercase tracking-wide">Usage this period</p>
                {subscription?.portalUrl && (
                  <a href={subscription.portalUrl} target="_blank" rel="noreferrer"
                    className="text-xs text-blue-600 flex items-center gap-1 hover:underline">
                    Manage billing <ExternalLink size={10} />
                  </a>
                )}
              </div>
              <div className="space-y-3">
                <UsageBar label="Credits used" used={creditsUsed} limit={allocated ?? 500} />
                {usage && <>
                  <UsageBar label="Intents executed" used={usage.intentsUsed ?? 0} limit={usage.intentsLimit ?? 100} color="#4f46e5" />
                  <UsageBar label="API calls"        used={usage.apiCallsUsed ?? 0} limit={usage.apiCallsLimit ?? 1000} color="#0d9488" />
                </>}
              </div>
            </Card>
          </div>

          {/* Gateway toggle */}
          <GatewayToggle gateway={gateway} onSwitch={handleGatewaySwitch} autoDetected={autoDetected} />

          {/* Tab bar */}
          <div className="flex gap-1 border-b border-slate-200">
            {[
              { id: 'plans',   label: 'Plans' },
              { id: 'credits', label: 'Credit packs' },
              { id: 'byok',    label: 'BYOK / BYOM' },
              { id: 'usage',   label: 'Usage details' },
            ].map(t => (
              <button key={t.id} onClick={() => setTab(t.id)}
                className={`px-4 py-2.5 text-sm font-medium border-b-2 transition-colors ${
                  tab === t.id
                    ? 'border-blue-600 text-blue-700'
                    : 'border-transparent text-slate-500 hover:text-slate-700'
                }`}>
                {t.label}
              </button>
            ))}
          </div>

          {/* ── Plans tab ───────────────────────────────────────────────────── */}
          {tab === 'plans' && (
            <div className="space-y-6">

              {/* ── Interval selector ─────────────────────────────────────── */}
              <div className="flex items-center justify-center gap-2 flex-wrap">
                {BILLING_INTERVALS.map(iv => (
                  <button key={iv.id} onClick={() => setInterval(iv.id)}
                    className={`relative px-4 py-2 rounded-xl text-sm font-medium border-2 transition-all ${
                      interval === iv.id
                        ? 'border-blue-600 bg-blue-600 text-white shadow-sm'
                        : 'border-slate-200 text-slate-600 hover:border-slate-300 bg-white'
                    }`}>
                    {iv.label}
                    {iv.badge && (
                      <span className="absolute -top-2.5 -right-2 bg-emerald-500 text-white text-[9px] font-bold px-1.5 py-0.5 rounded-full whitespace-nowrap">
                        {iv.badge}
                      </span>
                    )}
                  </button>
                ))}
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-5 gap-5">
                {PLANS.map(plan => (
                  <PlanCard key={plan.id} plan={plan}
                    currentPlanId={currentPlan}
                    onSelect={handleSelectPlan}
                    selecting={selecting}
                    gateway={gateway}
                    interval={interval} />
                ))}
              </div>

              {/* Model tier guide */}
              <Card>
                <CardHeader><CardTitle>Credit cost by AI model tier</CardTitle></CardHeader>
                <CardContent>
                  <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                    {Object.entries(MODEL_TIERS).map(([k, t]) => (
                      <div key={k} className="p-4 rounded-xl border-2"
                        style={{ borderColor: t.color + '44', backgroundColor: t.bg }}>
                        <div className="flex items-center justify-between mb-2">
                          <span className="text-sm font-bold" style={{ color: t.color }}>{t.label}</span>
                          <span className="text-lg font-extrabold" style={{ color: t.color }}>{t.credits} cr</span>
                        </div>
                        <p className="text-xs text-slate-600 mb-1">{t.models}</p>
                        <p className="text-xs text-slate-400">
                          {gateway === 'razorpay'
                            ? `≈ ₹${(t.credits * 0.064).toFixed(2)}/intent`
                            : `≈ $${(t.credits * 0.008).toFixed(3)}/intent`}
                        </p>
                      </div>
                    ))}
                  </div>
                  <p className="text-xs text-slate-400 text-center mt-3">
                    Each execution attempt costs credits · Retries charge per attempt · 1 credit ≈ $0.008
                  </p>
                </CardContent>
              </Card>

              <p className="text-xs text-slate-400 text-center">
                {gateway === 'stripe'
                  ? <>Payments processed by <a href="https://stripe.com" target="_blank" rel="noreferrer" className="text-blue-500 underline">Stripe</a>. Cancel anytime.</>
                  : <>Payments processed by <a href="https://razorpay.com" target="_blank" rel="noreferrer" className="text-blue-500 underline">Razorpay</a>. Prices in INR + taxes.</>
                }
              </p>
            </div>
          )}

          {/* ── Credit packs tab ────────────────────────────────────────────── */}
          {tab === 'credits' && (
            <div className="space-y-6">
              <p className="text-sm text-slate-600">
                One-time credit top-ups — stack on your plan, never expire.
                {gateway === 'razorpay' ? ' Prices in INR, charged via Razorpay.' : ''}
              </p>

              <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                {CREDIT_PACKS.map(pack => (
                  <PackCard key={pack.id} pack={pack}
                    onBuy={handleBuyPack}
                    selecting={selecting}
                    gateway={gateway} />
                ))}
              </div>

              <Card className="bg-blue-50 border-blue-200 shadow-none">
                <CardContent className="py-4 flex items-start gap-3">
                  <Zap size={16} className="text-blue-600 shrink-0 mt-0.5" />
                  <div className="text-xs text-blue-800">
                    <p className="font-semibold mb-1">Credits vs subscription — what's the difference?</p>
                    <p>
                      Your plan gives you a monthly credit allocation that resets each billing cycle.
                      Credit packs are one-time top-ups that stack on top of your monthly allocation
                      and <strong>never expire</strong>. Use packs for project spikes or to avoid overage charges.
                    </p>
                  </div>
                </CardContent>
              </Card>

              <p className="text-xs text-slate-400 text-center">
                {gateway === 'stripe'
                  ? <>Credits appear instantly after Stripe payment · Refunds within 24h if unused</>
                  : <>Credits appear after Razorpay payment confirms · UPI typically instant</>
                }
              </p>
            </div>
          )}

          {/* ── BYOK / BYOM tab ─────────────────────────────────────────────── */}
          {tab === 'byok' && (
            <div className="space-y-8">

              {/* ── Cost comparison banner ─────────────────────────────────── */}
              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                {[
                  { label: 'Standard tier cost',  value: '5 credits',  sub: 'GPT-4o · Claude Sonnet', color: '#2563eb', icon: <Zap size={15} /> },
                  { label: 'BYOK / BYOM cost',    value: '1 credit',   sub: 'Orchestration only',      color: '#16a34a', icon: <TrendingDown size={15} /> },
                  { label: 'Your savings',        value: '4 credits',  sub: '80% cost reduction',      color: '#7c3aed', icon: <Star size={15} /> },
                ].map(({ label, value, sub, color, icon }) => (
                  <div key={label} className="flex items-center gap-3 p-4 rounded-xl border border-slate-200 bg-white">
                    <div className="p-2.5 rounded-lg" style={{ background: color + '15', color }}>
                      {icon}
                    </div>
                    <div>
                      <p className="text-xs text-slate-500 mb-0.5">{label}</p>
                      <p className="text-lg font-extrabold" style={{ color }}>{value}</p>
                      <p className="text-[10px] text-slate-400">{sub}</p>
                    </div>
                  </div>
                ))}
              </div>

              {/* ── BYOK section ──────────────────────────────────────────── */}
              <div>
                <div className="flex items-center gap-2 mb-1">
                  <Key size={16} className="text-slate-700" />
                  <h3 className="text-sm font-bold text-slate-900">BYOK — Bring Your Own Key</h3>
                  <span className="text-[10px] font-medium px-2 py-0.5 rounded-full bg-blue-50 text-blue-700 border border-blue-200">
                    Pro + Enterprise
                  </span>
                </div>
                <p className="text-xs text-slate-500 mb-4 ml-6">
                  Connect your existing AI provider contracts. DecisionMesh charges 1 credit per execution
                  for orchestration, policy enforcement, and audit — you pay your provider directly.
                </p>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  {BYOK_PROVIDERS.map(provider => {
                    const keyVal   = byokKeys[provider.id]   ?? '';
                    const isVisible = byokVisible[provider.id] ?? false;
                    const isSaved   = byokSaved[provider.id]  ?? false;
                    const isSaving  = byokSaving === provider.id;

                    return (
                      <div key={provider.id}
                        className="rounded-xl border-2 p-4 transition-all"
                        style={{ borderColor: isSaved ? provider.color + '55' : '#e2e8f0',
                                 backgroundColor: isSaved ? provider.bg : 'white' }}>

                        {/* Header */}
                        <div className="flex items-center justify-between mb-3">
                          <div className="flex items-center gap-2">
                            <span className="text-lg">{provider.logo}</span>
                            <div>
                              <p className="text-sm font-bold text-slate-900">{provider.name}</p>
                              <p className="text-[10px] text-slate-400">{provider.models}</p>
                            </div>
                          </div>
                          <div className="flex items-center gap-2">
                            {isSaved && (
                              <span className="flex items-center gap-1 text-[10px] font-medium px-2 py-0.5 rounded-full"
                                style={{ background: provider.color + '20', color: provider.color }}>
                                <Check size={9} /> Connected
                              </span>
                            )}
                            <a href={provider.docsUrl} target="_blank" rel="noreferrer"
                              className="text-[10px] text-slate-400 hover:text-slate-600 flex items-center gap-0.5">
                              Get key <ExternalLink size={9} />
                            </a>
                          </div>
                        </div>

                        {/* Key input */}
                        <div className="relative mb-3">
                          <input
                            type={isVisible ? 'text' : 'password'}
                            value={keyVal}
                            onChange={e => setByokKeys(k => ({ ...k, [provider.id]: e.target.value }))}
                            placeholder={isSaved ? '••••••••••••••••••••••••' : provider.placeholder}
                            className="w-full text-xs font-mono border border-slate-200 rounded-lg px-3 py-2.5 pr-9 bg-slate-50 focus:outline-none focus:border-blue-400 focus:bg-white transition-colors"
                            style={{ fontFamily: "'JetBrains Mono', monospace" }}
                          />
                          <button
                            onClick={() => setByokVisible(v => ({ ...v, [provider.id]: !isVisible }))}
                            className="absolute right-2.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600">
                            {isVisible ? <EyeOff size={13} /> : <Eye size={13} />}
                          </button>
                        </div>

                        {/* Cost note */}
                        <div className="flex items-center gap-1.5 mb-3 text-[10px] text-slate-500">
                          <Zap size={10} style={{ color: provider.color }} />
                          <span>1 credit per execution · you pay {provider.name} directly at your contract rate</span>
                        </div>

                        {/* Actions */}
                        <div className="flex gap-2">
                          <button
                            onClick={() => handleSaveByokKey(provider.id)}
                            disabled={!keyVal.trim() || isSaving}
                            className="flex-1 flex items-center justify-center gap-1.5 py-2 rounded-lg text-xs font-semibold text-white transition-opacity disabled:opacity-40"
                            style={{ backgroundColor: provider.color }}>
                            {isSaving
                              ? <span className="w-3 h-3 border-2 border-white border-t-transparent rounded-full animate-spin" />
                              : <><Check size={11} /> {isSaved ? 'Update key' : 'Save key'}</>
                            }
                          </button>
                          {isSaved && (
                            <button
                              onClick={() => handleDeleteByokKey(provider.id)}
                              className="px-3 py-2 rounded-lg text-xs font-medium border border-red-200 text-red-500 hover:bg-red-50 transition-colors">
                              <Trash2 size={11} />
                            </button>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>

                {/* BYOK info card */}
                <div className="mt-4 p-4 rounded-xl bg-amber-50 border border-amber-200 flex items-start gap-3">
                  <Shield size={15} className="text-amber-600 shrink-0 mt-0.5" />
                  <div className="text-xs text-amber-800 space-y-1">
                    <p className="font-semibold">How your keys are stored</p>
                    <p>Keys are encrypted at rest using AES-256 and never logged or exposed in audit trails.
                       DecisionMesh uses your key only when executing intents you submit.
                       You can revoke access at any time by deleting the key above.</p>
                  </div>
                </div>
              </div>

              {/* ── Divider ────────────────────────────────────────────────── */}
              <div className="border-t border-slate-200" />

              {/* ── BYOM section ──────────────────────────────────────────── */}
              <div>
                <div className="flex items-center gap-2 mb-1">
                  <Cpu size={16} className="text-slate-700" />
                  <h3 className="text-sm font-bold text-slate-900">BYOM — Bring Your Own Model</h3>
                  <span className="text-[10px] font-medium px-2 py-0.5 rounded-full bg-purple-50 text-purple-700 border border-purple-200">
                    Enterprise
                  </span>
                </div>
                <p className="text-xs text-slate-500 mb-4 ml-6">
                  Run your own model on your own infrastructure — LayoutLMv3, Ollama, or any REST endpoint.
                  DecisionMesh wraps it with budget enforcement, policy governance, and full audit.
                  Zero data leaves your premises. 1 credit per execution.
                </p>

                {/* Existing endpoints */}
                {byomEndpoints.length > 0 && (
                  <div className="space-y-3 mb-4">
                    {byomEndpoints.map(ep => {
                      const modelType = BYOM_TYPES.find(t => t.id === ep.type) ?? BYOM_TYPES[3];
                      return (
                        <div key={ep.id}
                          className="flex items-center gap-3 p-3.5 rounded-xl border border-slate-200 bg-white">
                          <div className="p-2 rounded-lg"
                            style={{ background: modelType.color + '15', color: modelType.color }}>
                            <Server size={14} />
                          </div>
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2">
                              <p className="text-sm font-semibold text-slate-800 truncate">{ep.name}</p>
                              <span className="text-[10px] font-medium px-1.5 py-0.5 rounded-full flex-shrink-0"
                                style={{ background: modelType.color + '15', color: modelType.color }}>
                                {modelType.label}
                              </span>
                              {ep.active && (
                                <span className="flex items-center gap-1 text-[10px] text-green-700 bg-green-50 px-1.5 py-0.5 rounded-full flex-shrink-0">
                                  <Wifi size={8} /> Live
                                </span>
                              )}
                            </div>
                            <p className="text-[10px] font-mono text-slate-400 truncate mt-0.5">{ep.url}</p>
                          </div>
                          <div className="flex items-center gap-2 flex-shrink-0">
                            <span className="text-[10px] text-slate-500 flex items-center gap-1">
                              <Zap size={9} className="text-purple-500" /> 1 cr/call
                            </span>
                            <button
                              onClick={() => handleDeleteByomEndpoint(ep.id)}
                              className="p-1.5 rounded-lg border border-red-200 text-red-400 hover:bg-red-50 transition-colors">
                              <Trash2 size={11} />
                            </button>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}

                {/* Add endpoint form */}
                {byomAdding ? (
                  <div className="rounded-xl border-2 border-purple-200 bg-purple-50 p-5 space-y-3">
                    <p className="text-xs font-semibold text-purple-900">Register model endpoint</p>

                    {/* Model type selector */}
                    <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
                      {BYOM_TYPES.map(t => (
                        <button key={t.id} type="button"
                          onClick={() => setByomForm(f => ({ ...f, type: t.id }))}
                          className="text-left p-2.5 rounded-lg border-2 transition-all text-xs"
                          style={{
                            borderColor:     byomForm.type === t.id ? t.color : '#e2e8f0',
                            backgroundColor: byomForm.type === t.id ? t.color + '12' : 'white',
                          }}>
                          <div className="font-semibold mb-0.5" style={{ color: byomForm.type === t.id ? t.color : '#475569' }}>
                            {t.label}
                          </div>
                          <div className="text-slate-400 text-[10px] leading-tight">{t.sub}</div>
                        </button>
                      ))}
                    </div>

                    {/* Name + URL */}
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                      <div>
                        <label className="text-[10px] font-medium text-slate-600 mb-1 block">Display name</label>
                        <input
                          type="text"
                          value={byomForm.name}
                          onChange={e => setByomForm(f => ({ ...f, name: e.target.value }))}
                          placeholder="e.g. Invoice Extractor (LayoutLMv3)"
                          className="w-full text-xs border border-slate-200 rounded-lg px-3 py-2 bg-white focus:outline-none focus:border-purple-400 transition-colors"
                        />
                      </div>
                      <div>
                        <label className="text-[10px] font-medium text-slate-600 mb-1 block">Endpoint URL</label>
                        <input
                          type="url"
                          value={byomForm.url}
                          onChange={e => setByomForm(f => ({ ...f, url: e.target.value }))}
                          placeholder="http://your-server:8001/classify"
                          className="w-full text-xs font-mono border border-slate-200 rounded-lg px-3 py-2 bg-white focus:outline-none focus:border-purple-400 transition-colors"
                          style={{ fontFamily: "'JetBrains Mono', monospace" }}
                        />
                      </div>
                    </div>

                    {/* Auth header */}
                    <div>
                      <label className="text-[10px] font-medium text-slate-600 mb-1 block">
                        Auth header <span className="text-slate-400 font-normal">(optional — e.g. Bearer your-token)</span>
                      </label>
                      <input
                        type="text"
                        value={byomForm.authHeader}
                        onChange={e => setByomForm(f => ({ ...f, authHeader: e.target.value }))}
                        placeholder="Bearer sk-..."
                        className="w-full text-xs font-mono border border-slate-200 rounded-lg px-3 py-2 bg-white focus:outline-none focus:border-purple-400 transition-colors"
                        style={{ fontFamily: "'JetBrains Mono', monospace" }}
                      />
                    </div>

                    {/* Cost note */}
                    <div className="flex items-center gap-1.5 text-[10px] text-purple-700">
                      <Zap size={10} />
                      <span>1 credit per execution · DecisionMesh enforces your budget + policy on every call to this endpoint</span>
                    </div>

                    {/* Actions */}
                    <div className="flex gap-2">
                      <button
                        onClick={handleAddByomEndpoint}
                        disabled={!byomForm.name.trim() || !byomForm.url.trim() || byomSaving}
                        className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-xs font-semibold text-white bg-purple-600 hover:bg-purple-700 disabled:opacity-40 transition-colors">
                        {byomSaving
                          ? <span className="w-3 h-3 border-2 border-white border-t-transparent rounded-full animate-spin" />
                          : <><Link2 size={11} /> Register endpoint</>
                        }
                      </button>
                      <button
                        onClick={() => { setByomAdding(false); setByomForm({ name: '', url: '', type: 'ollama', authHeader: '' }); }}
                        className="px-4 py-2 rounded-lg text-xs font-medium border border-slate-200 text-slate-600 hover:bg-slate-50 transition-colors">
                        Cancel
                      </button>
                    </div>
                  </div>
                ) : (
                  <button
                    onClick={() => setByomAdding(true)}
                    className="flex items-center gap-2 px-4 py-3 w-full rounded-xl border-2 border-dashed border-slate-200 hover:border-purple-300 hover:bg-purple-50 text-slate-500 hover:text-purple-700 text-xs font-medium transition-all">
                    <Plus size={14} /> Register a model endpoint
                  </button>
                )}

                {/* BYOM info card */}
                <div className="mt-4 p-4 rounded-xl bg-purple-50 border border-purple-200 flex items-start gap-3">
                  <Server size={15} className="text-purple-600 shrink-0 mt-0.5" />
                  <div className="text-xs text-purple-900 space-y-1">
                    <p className="font-semibold">How BYOM works</p>
                    <p>DecisionMesh sends your intent payload to your model endpoint and wraps the call
                       with budget ceiling enforcement, retry policy, quality scoring, drift detection,
                       and a full immutable audit trail — exactly the same governance as any other adapter.
                       Your document and model output never touch DecisionMesh servers.</p>
                    <p className="pt-1">
                      <strong>Compatible models:</strong> LayoutLMv3 (ONNX or REST), Ollama (any model),
                       HuggingFace Inference Endpoints, spaCy serving, custom FastAPI services.
                    </p>
                  </div>
                </div>

                {/* BYOM vs BYOK comparison */}
                <div className="mt-4 grid grid-cols-1 md:grid-cols-2 gap-3">
                  {[
                    {
                      title: 'BYOK',
                      icon: <Key size={13} />,
                      color: '#D97706',
                      bg: '#FFFBEB',
                      items: [
                        'Your API key, provider\'s infrastructure',
                        'Data sent to Anthropic / OpenAI / etc.',
                        'Best for: cost reduction on existing contracts',
                        'Privacy: same as using provider directly',
                        '1 credit · orchestration charge only',
                      ],
                    },
                    {
                      title: 'BYOM',
                      icon: <Cpu size={13} />,
                      color: '#7C3AED',
                      bg: '#F5F3FF',
                      items: [
                        'Your model, your infrastructure',
                        'Data never leaves your servers',
                        'Best for: HIPAA, legal, sensitive docs',
                        'Privacy: complete — zero external calls',
                        '1 credit · governance charge only',
                      ],
                    },
                  ].map(({ title, icon, color, bg, items }) => (
                    <div key={title} className="rounded-xl border p-4" style={{ borderColor: color + '33', background: bg }}>
                      <div className="flex items-center gap-1.5 mb-3" style={{ color }}>
                        {icon}
                        <span className="text-xs font-bold">{title}</span>
                      </div>
                      <ul className="space-y-1.5">
                        {items.map(item => (
                          <li key={item} className="flex items-start gap-1.5 text-[11px] text-slate-600">
                            <Check size={10} className="shrink-0 mt-0.5" style={{ color }} />
                            {item}
                          </li>
                        ))}
                      </ul>
                    </div>
                  ))}
                </div>
              </div>

              {/* ── Plan eligibility note ──────────────────────────────────── */}
              <div className="p-4 rounded-xl bg-slate-50 border border-slate-200 flex items-start gap-3">
                <Info size={14} className="text-slate-500 shrink-0 mt-0.5" />
                <div className="text-xs text-slate-600">
                  <span className="font-semibold">Plan requirements: </span>
                  BYOK is available on <strong>Pro and Enterprise</strong> plans.
                  BYOM is available on <strong>Enterprise</strong> plans only.
                  {currentPlan === 'free' || currentPlan === 'hobby' || currentPlan === 'builder' ? (
                    <> Your current plan is <strong className="capitalize">{currentPlan}</strong>.{' '}
                      <button onClick={() => setTab('plans')} className="text-blue-600 underline">
                        Upgrade to unlock →
                      </button>
                    </>
                  ) : (
                    <> Your current plan is <strong className="capitalize">{currentPlan}</strong> — you\'re eligible.</>
                  )}
                </div>
              </div>

            </div>
          )}

          {/* ── Usage details tab ───────────────────────────────────────────── */}
          {tab === 'usage' && (
            <div className="space-y-4">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {[
                  { label: 'Credits used',    used: creditsUsed,          limit: allocated ?? 500,   color: '#16a34a' },
                  { label: 'Intents executed', used: usage?.intentsUsed  ?? 0, limit: usage?.intentsLimit  ?? 100,  color: '#2563eb' },
                  { label: 'API calls',        used: usage?.apiCallsUsed ?? 0, limit: usage?.apiCallsLimit ?? 1000, color: '#0d9488' },
                ].map(({ label, used, limit, color }) => (
                  <Card key={label} className="p-5">
                    <div className="flex justify-between mb-2">
                      <p className="text-sm font-medium text-slate-700">{label}</p>
                      <p className="text-sm font-bold text-slate-900">
                        {used.toLocaleString()} / {limit.toLocaleString()}
                      </p>
                    </div>
                    <div className="h-2 bg-slate-100 rounded-full overflow-hidden mb-2">
                      <div className="h-full rounded-full" style={{
                        width:           `${Math.min(100, (used / limit) * 100)}%`,
                        backgroundColor: color,
                      }} />
                    </div>
                    <p className="text-xs text-slate-400">
                      {(limit - used).toLocaleString()} remaining
                      {usage?.periodEnd ? ` · resets ${usage.periodEnd}` : ''}
                    </p>
                  </Card>
                ))}
              </div>

              <Card className="p-5">
                <p className="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-3">Period</p>
                <div className="flex gap-8 text-sm">
                  <div>
                    <p className="text-slate-400 text-xs mb-0.5">Start</p>
                    <p className="font-medium text-slate-700">{usage?.periodStart ?? '—'}</p>
                  </div>
                  <div>
                    <p className="text-slate-400 text-xs mb-0.5">End</p>
                    <p className="font-medium text-slate-700">{usage?.periodEnd ?? '—'}</p>
                  </div>
                  <div>
                    <p className="text-slate-400 text-xs mb-0.5">Credits remaining</p>
                    <p className="font-bold text-slate-900">{balance?.toLocaleString() ?? '—'}</p>
                  </div>
                </div>
              </Card>

              <div className="flex justify-center">
                <button
                  onClick={() => setTab('credits')}
                  className="flex items-center gap-2 px-4 py-2.5 bg-blue-600 text-white text-sm font-medium rounded-xl hover:bg-blue-700 transition-colors">
                  <ShoppingCart size={14} /> Buy more credits
                </button>
              </div>
            </div>
          )}
        </>
      )}
    </Page>
  );
}
