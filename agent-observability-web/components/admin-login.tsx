"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";

export function AdminLogin({
  copy,
}: {
  copy: {
    title: string;
    description: string;
    adminKey: string;
    signIn: string;
    signingIn: string;
    requestFailed: string;
  };
}) {
  const router = useRouter();
  const [key, setKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      const response = await fetch("/api/admin/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ adminKey: key }),
      });
      if (!response.ok) throw new Error(copy.requestFailed);
      setKey("");
      router.refresh();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : copy.requestFailed);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="admin-login panel">
      <div className="empty-icon">⌁</div>
      <p className="eyebrow">Admin</p>
      <h1>{copy.title}</h1>
      <p>{copy.description}</p>
      <form onSubmit={submit}>
        <label>
          <span>{copy.adminKey}</span>
          <input
            autoComplete="current-password"
            autoFocus
            onChange={(event) => setKey(event.target.value)}
            required
            type="password"
            value={key}
          />
        </label>
        {error && <p className="form-error">{error}</p>}
        <button className="primary-action" disabled={busy} type="submit">
          {busy ? copy.signingIn : copy.signIn}
        </button>
      </form>
    </section>
  );
}
