"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import type {
  Application,
  ApplicationWithKey,
} from "../lib/application-types";
import type { Locale } from "../lib/i18n";

interface ApplicationCopy {
  eyebrow: string;
  title: string;
  description: string;
  createTitle: string;
  name: string;
  namePlaceholder: string;
  descriptionLabel: string;
  descriptionPlaceholder: string;
  create: string;
  creating: string;
  listTitle: string;
  applicationCount: string;
  emptyTitle: string;
  emptyDescription: string;
  keyHint: string;
  createdAt: string;
  updatedAt: string;
  edit: string;
  save: string;
  saving: string;
  cancel: string;
  rotate: string;
  rotating: string;
  delete: string;
  deleting: string;
  rotateConfirm: string;
  deleteConfirm: string;
  secretTitle: string;
  secretDescription: string;
  copy: string;
  copied: string;
  close: string;
  requestFailed: string;
  signOut: string;
}

export function ApplicationsManager({
  initialApplications,
  locale,
  showLogout,
  copy,
}: {
  initialApplications: Application[];
  locale: Locale;
  showLogout: boolean;
  copy: ApplicationCopy;
}) {
  const router = useRouter();
  const [applications, setApplications] = useState(initialApplications);
  const [createName, setCreateName] = useState("");
  const [createDescription, setCreateDescription] = useState("");
  const [editing, setEditing] = useState<Application | null>(null);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [secret, setSecret] = useState<{ name: string; key: string } | null>(null);
  const [copied, setCopied] = useState(false);

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy("create");
    setError("");
    try {
      const created = await api<ApplicationWithKey>("/api/applications", {
        method: "POST",
        body: JSON.stringify({
          name: createName,
          description: createDescription,
        }),
      });
      setApplications((current) => [...current, created.application]);
      setCreateName("");
      setCreateDescription("");
      showSecret(created);
    } catch (failure) {
      showError(failure);
    } finally {
      setBusy("");
    }
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!editing) return;
    setBusy(`save:${editing.id}`);
    setError("");
    try {
      const result = await api<{ application: Application }>(
        `/api/applications/${encodeURIComponent(editing.id)}`,
        {
          method: "PATCH",
          body: JSON.stringify({
            name: editing.name,
            description: editing.description,
          }),
        },
      );
      replace(result.application);
      setEditing(null);
    } catch (failure) {
      showError(failure);
    } finally {
      setBusy("");
    }
  }

  async function rotate(application: Application) {
    if (!window.confirm(copy.rotateConfirm)) return;
    setBusy(`rotate:${application.id}`);
    setError("");
    try {
      const rotated = await api<ApplicationWithKey>(
        `/api/applications/${encodeURIComponent(application.id)}/rotate-key`,
        { method: "POST" },
      );
      replace(rotated.application);
      showSecret(rotated);
    } catch (failure) {
      showError(failure);
    } finally {
      setBusy("");
    }
  }

  async function remove(application: Application) {
    if (!window.confirm(copy.deleteConfirm)) return;
    setBusy(`delete:${application.id}`);
    setError("");
    try {
      await api<{ deleted: boolean }>(
        `/api/applications/${encodeURIComponent(application.id)}`,
        { method: "DELETE" },
      );
      setApplications((current) =>
        current.filter((candidate) => candidate.id !== application.id),
      );
      if (editing?.id === application.id) setEditing(null);
    } catch (failure) {
      showError(failure);
    } finally {
      setBusy("");
    }
  }

  async function logout() {
    await fetch("/api/admin/logout", { method: "POST" });
    router.refresh();
  }

  function replace(application: Application) {
    setApplications((current) =>
      current.map((candidate) =>
        candidate.id === application.id ? application : candidate,
      ),
    );
  }

  function showSecret(result: ApplicationWithKey) {
    setCopied(false);
    setSecret({ name: result.application.name, key: result.apiKey });
  }

  function showError(failure: unknown) {
    setError(failure instanceof Error ? failure.message : copy.requestFailed);
  }

  async function copySecret() {
    if (!secret) return;
    try {
      await navigator.clipboard.writeText(secret.key);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  async function api<T>(url: string, options: RequestInit): Promise<T> {
    const response = await fetch(url, {
      ...options,
      headers: { "Content-Type": "application/json", ...options.headers },
    });
    const result = (await response.json()) as T & { error?: string };
    if (!response.ok) throw new Error(result.error || copy.requestFailed);
    return result;
  }

  return (
    <>
      <section className="hero applications-hero">
        <div>
          <p className="eyebrow">{copy.eyebrow}</p>
          <h1>{copy.title}</h1>
          <p className="hero-copy">{copy.description}</p>
        </div>
        {showLogout && (
          <button className="secondary-action" onClick={logout} type="button">
            {copy.signOut}
          </button>
        )}
      </section>

      {error && <div className="application-error">{error}</div>}

      <section className="application-layout">
        <article className="panel create-application">
          <p className="eyebrow">{copy.createTitle}</p>
          <form onSubmit={create}>
            <label>
              <span>{copy.name}</span>
              <input
                maxLength={120}
                onChange={(event) => setCreateName(event.target.value)}
                placeholder={copy.namePlaceholder}
                required
                value={createName}
              />
            </label>
            <label>
              <span>{copy.descriptionLabel}</span>
              <textarea
                maxLength={2_000}
                onChange={(event) => setCreateDescription(event.target.value)}
                placeholder={copy.descriptionPlaceholder}
                rows={4}
                value={createDescription}
              />
            </label>
            <button className="primary-action" disabled={busy === "create"} type="submit">
              {busy === "create" ? copy.creating : copy.create}
            </button>
          </form>
        </article>

        <section className="panel application-list">
          <div className="panel-heading">
            <div>
              <p className="eyebrow">{copy.listTitle}</p>
              <h2>{applications.length} {copy.applicationCount}</h2>
            </div>
          </div>
          {applications.length === 0 ? (
            <div className="applications-empty">
              <div className="empty-icon">A</div>
              <h3>{copy.emptyTitle}</h3>
              <p>{copy.emptyDescription}</p>
            </div>
          ) : (
            <div className="application-items">
              {applications.map((application) =>
                editing?.id === application.id ? (
                  <form className="application-edit" key={application.id} onSubmit={save}>
                    <input
                      maxLength={120}
                      onChange={(event) =>
                        setEditing({ ...editing, name: event.target.value })
                      }
                      required
                      value={editing.name}
                    />
                    <textarea
                      maxLength={2_000}
                      onChange={(event) =>
                        setEditing({ ...editing, description: event.target.value })
                      }
                      rows={3}
                      value={editing.description}
                    />
                    <div className="application-actions">
                      <button className="primary-action small" disabled={busy === `save:${application.id}`} type="submit">
                        {busy === `save:${application.id}` ? copy.saving : copy.save}
                      </button>
                      <button className="secondary-action small" onClick={() => setEditing(null)} type="button">
                        {copy.cancel}
                      </button>
                    </div>
                  </form>
                ) : (
                  <article className="application-item" key={application.id}>
                    <div className="application-main">
                      <span className="agent-glyph large">
                        {application.name.slice(0, 1).toUpperCase()}
                      </span>
                      <div>
                        <h3>{application.name}</h3>
                        <p>{application.description || "—"}</p>
                        <code>{application.id}</code>
                      </div>
                    </div>
                    <dl className="application-metadata">
                      <div><dt>{copy.keyHint}</dt><dd><code>{application.keyHint}</code></dd></div>
                      <div><dt>{copy.createdAt}</dt><dd>{formatDate(application.createdAt, locale)}</dd></div>
                      <div><dt>{copy.updatedAt}</dt><dd>{formatDate(application.updatedAt, locale)}</dd></div>
                    </dl>
                    <div className="application-actions">
                      <button className="secondary-action small" onClick={() => setEditing(application)} type="button">{copy.edit}</button>
                      <button className="secondary-action small" disabled={busy === `rotate:${application.id}`} onClick={() => rotate(application)} type="button">
                        {busy === `rotate:${application.id}` ? copy.rotating : copy.rotate}
                      </button>
                      <button className="danger-action small" disabled={busy === `delete:${application.id}`} onClick={() => remove(application)} type="button">
                        {busy === `delete:${application.id}` ? copy.deleting : copy.delete}
                      </button>
                    </div>
                  </article>
                ),
              )}
            </div>
          )}
        </section>
      </section>

      {secret && (
        <div className="secret-backdrop" role="presentation">
          <section aria-labelledby="secret-title" aria-modal="true" className="secret-dialog" role="dialog">
            <div className="secret-icon">◇</div>
            <p className="eyebrow">{secret.name}</p>
            <h2 id="secret-title">{copy.secretTitle}</h2>
            <p>{copy.secretDescription}</p>
            <div className="secret-value">
              <code>{secret.key}</code>
              <button onClick={copySecret} type="button">
                {copied ? copy.copied : copy.copy}
              </button>
            </div>
            <button className="primary-action" onClick={() => setSecret(null)} type="button">
              {copy.close}
            </button>
          </section>
        </div>
      )}
    </>
  );

}

function formatDate(value: string, locale: Locale) {
  return new Intl.DateTimeFormat(locale, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}
