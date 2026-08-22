"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

export function RefreshControl({
  automaticLabel,
  refreshLabel,
}: {
  automaticLabel: string;
  refreshLabel: string;
}) {
  const router = useRouter();
  const [automatic, setAutomatic] = useState(false);

  useEffect(() => {
    if (!automatic) return;
    const timer = window.setInterval(() => router.refresh(), 5_000);
    return () => window.clearInterval(timer);
  }, [automatic, router]);

  return (
    <div className="refresh-control">
      <label className="auto-refresh">
        <input
          type="checkbox"
          checked={automatic}
          onChange={(event) => setAutomatic(event.target.checked)}
        />
        <span>{automaticLabel}</span>
      </label>
      <button className="refresh-button" onClick={() => router.refresh()}>
        {refreshLabel}
      </button>
    </div>
  );
}
