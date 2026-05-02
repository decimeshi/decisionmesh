import { useState } from 'react';
import { Zap, User, Building2, ArrowRight, Loader2 } from 'lucide-react';
import { setupTenant } from '../utils/api';

// ── Shared redirect after successful onboarding ───────────────────────────────
async function redirectAfterOnboarding(keycloak) {
    keycloak.tokenParsed.exp = 0;
    try {
        await keycloak.updateToken(-1);
        await new Promise(r => setTimeout(r, 400));
        window.location.href = '/';
    } catch {
        // Silent refresh failed — force full re-login to get fresh token
        keycloak.login();
    }
}

export default function Onboarding({ keycloak }) {
    const [accountType, setAccountType] = useState('INDIVIDUAL');
    const [companyName, setCompanyName] = useState('');
    const [companySize, setCompanySize] = useState('');
    const [loading,     setLoading]     = useState(false);
    const [error,       setError]       = useState('');
    const [done,        setDone]        = useState(false);

    async function handleSubmit() {
        setError('');

        if (accountType === 'ORGANIZATION' && !companyName.trim()) {
            setError('Please enter your company name.');
            return;
        }

        setLoading(true);
        try {
            const result = await setupTenant(keycloak, {
                accountType,
                ...(accountType === 'ORGANIZATION' && {
                    companyName: companyName.trim(),
                    companySize: companySize.trim() || undefined,
                }),
            });

            // null means unauthenticated — request() returns null, not throws
            if (!result) throw new Error('Session expired — please log in again.');

            // ── Success ───────────────────────────────────────────────────────
            setDone(true);
            await redirectAfterOnboarding(keycloak);

        } catch (err) {
            // ── 409 Conflict = tenant already exists = treat as success ───────
            // This happens if user clicked twice or page was refreshed mid-flow
            if (err?.status === 409 || err?.message?.includes('already')) {
                setDone(true);
                await redirectAfterOnboarding(keycloak);
                return;
            }
            // ── Any other error — show message ────────────────────────────────
            setError(err?.message || 'Something went wrong. Please try again.');
        } finally {
            setLoading(false);
        }
    }

    // ── Success state ─────────────────────────────────────────────────────────
    if (done) {
        return (
            <div className="min-h-screen bg-slate-50 flex items-center justify-center p-4">
                <div className="bg-white rounded-2xl border border-slate-200 shadow-sm p-10 w-full max-w-sm text-center">
                    <div className="w-14 h-14 rounded-full bg-green-50 flex items-center justify-center mx-auto mb-4">
                        <svg width="26" height="26" viewBox="0 0 24 24" fill="none"
                             stroke="#16a34a" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                            <polyline points="20 6 9 17 4 12" />
                        </svg>
                    </div>
                    <p className="text-base font-semibold text-slate-800 mb-1">You're all set!</p>
                    <p className="text-sm text-slate-500">Setting up your workspace…</p>
                </div>
            </div>
        );
    }

    // ── Main form ─────────────────────────────────────────────────────────────
    return (
        <div className="min-h-screen bg-slate-50 flex items-center justify-center p-4">
            <div className="w-full max-w-md">

                {/* Logo */}
                <div className="flex items-center gap-2 mb-8">
                    <div className="flex items-center justify-center w-7 h-7 rounded-lg bg-blue-600 shrink-0">
                        <Zap size={14} className="text-white" />
                    </div>
                    <span className="text-sm font-semibold text-slate-800 tracking-tight">
                        DecisionMesh
                    </span>
                </div>

                {/* Card */}
                <div className="bg-white rounded-2xl border border-slate-200 shadow-sm p-8">
                    <h1 className="text-xl font-semibold text-slate-900 mb-1">
                        Welcome! One last step.
                    </h1>
                    <p className="text-sm text-slate-500 mb-7">
                        Tell us how you'll be using DecisionMesh.
                    </p>

                    {/* Account type selector */}
                    <p className="text-xs font-medium text-slate-500 uppercase tracking-wider mb-3">
                        I'm signing up as
                    </p>
                    <div className="grid grid-cols-2 gap-3 mb-6">

                        {/* Individual */}
                        <button
                            onClick={() => setAccountType('INDIVIDUAL')}
                            className={`flex flex-col items-start gap-2 p-4 rounded-xl border-2 text-left transition-all
                                ${accountType === 'INDIVIDUAL'
                                    ? 'border-blue-600 bg-blue-50'
                                    : 'border-slate-200 hover:border-slate-300 bg-white'
                                }`}
                        >
                            <div className={`w-8 h-8 rounded-lg flex items-center justify-center
                                ${accountType === 'INDIVIDUAL' ? 'bg-blue-600' : 'bg-slate-100'}`}>
                                <User size={15} className={accountType === 'INDIVIDUAL' ? 'text-white' : 'text-slate-500'} />
                            </div>
                            <div>
                                <p className={`text-sm font-semibold ${accountType === 'INDIVIDUAL' ? 'text-blue-700' : 'text-slate-700'}`}>
                                    Individual
                                </p>
                                <p className="text-xs text-slate-400 mt-0.5">Personal or freelance</p>
                            </div>
                        </button>

                        {/* Organization */}
                        <button
                            onClick={() => setAccountType('ORGANIZATION')}
                            className={`flex flex-col items-start gap-2 p-4 rounded-xl border-2 text-left transition-all
                                ${accountType === 'ORGANIZATION'
                                    ? 'border-blue-600 bg-blue-50'
                                    : 'border-slate-200 hover:border-slate-300 bg-white'
                                }`}
                        >
                            <div className={`w-8 h-8 rounded-lg flex items-center justify-center
                                ${accountType === 'ORGANIZATION' ? 'bg-blue-600' : 'bg-slate-100'}`}>
                                <Building2 size={15} className={accountType === 'ORGANIZATION' ? 'text-white' : 'text-slate-500'} />
                            </div>
                            <div>
                                <p className={`text-sm font-semibold ${accountType === 'ORGANIZATION' ? 'text-blue-700' : 'text-slate-700'}`}>
                                    Organization
                                </p>
                                <p className="text-xs text-slate-400 mt-0.5">Team or company</p>
                            </div>
                        </button>
                    </div>

                    {/* Org fields */}
                    {accountType === 'ORGANIZATION' && (
                        <div className="space-y-4 mb-6 border-t border-slate-100 pt-5">
                            <div>
                                <label className="block text-xs font-medium text-slate-600 mb-1.5">
                                    Company Name <span className="text-red-500">*</span>
                                </label>
                                <input
                                    type="text"
                                    value={companyName}
                                    onChange={e => setCompanyName(e.target.value)}
                                    placeholder="e.g. Acme Inc."
                                    className="w-full px-3 py-2.5 text-sm rounded-lg border border-slate-200
                                        focus:outline-none focus:ring-2 focus:ring-blue-600 focus:border-transparent
                                        placeholder:text-slate-300 text-slate-800 transition"
                                />
                            </div>
                            <div>
                                <label className="block text-xs font-medium text-slate-600 mb-1.5">
                                    Company Size <span className="text-slate-400 font-normal">(optional)</span>
                                </label>
                                <select
                                    value={companySize}
                                    onChange={e => setCompanySize(e.target.value)}
                                    className="w-full px-3 py-2.5 text-sm rounded-lg border border-slate-200
                                        focus:outline-none focus:ring-2 focus:ring-blue-600 focus:border-transparent
                                        text-slate-800 bg-white transition"
                                >
                                    <option value="">Select size…</option>
                                    <option value="1-10">1–10 employees</option>
                                    <option value="11-50">11–50 employees</option>
                                    <option value="51-200">51–200 employees</option>
                                    <option value="201-500">201–500 employees</option>
                                    <option value="500+">500+ employees</option>
                                </select>
                            </div>
                        </div>
                    )}

                    {/* Error */}
                    {error && (
                        <div className="mb-5 px-3.5 py-2.5 rounded-lg bg-red-50 border border-red-100">
                            <p className="text-xs text-red-600">{error}</p>
                        </div>
                    )}

                    {/* Submit */}
                    <button
                        onClick={handleSubmit}
                        disabled={loading}
                        className="w-full flex items-center justify-center gap-2 py-2.5 px-4
                            bg-blue-600 hover:bg-blue-700 disabled:opacity-60 disabled:cursor-not-allowed
                            text-white text-sm font-medium rounded-lg transition-colors"
                    >
                        {loading ? (
                            <Loader2 size={16} className="animate-spin" />
                        ) : (
                            <>
                                Continue to Dashboard
                                <ArrowRight size={15} />
                            </>
                        )}
                    </button>
                </div>

                <p className="text-center text-xs text-slate-400 mt-5">
                    Signed in as{' '}
                    <span className="font-medium text-slate-600">
                        {keycloak?.tokenParsed?.email}
                    </span>
                </p>
            </div>
        </div>
    );
}
