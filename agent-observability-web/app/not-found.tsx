import Link from "next/link";

export default function NotFound() {
  return (
    <div className="empty-state standalone">
      <div className="empty-icon">404</div>
      <h1>Turn not found</h1>
      <p>The trace may have expired under the configured retention limit.</p>
      <Link className="refresh-button" href="/">Back to turns</Link>
    </div>
  );
}
