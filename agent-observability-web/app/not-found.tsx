import Link from "next/link";
import { currentLocale } from "../lib/current-locale";
import { dictionary } from "../lib/i18n";

export default async function NotFound() {
  const copy = dictionary(await currentLocale()).notFound;
  return (
    <div className="empty-state standalone">
      <div className="empty-icon">404</div>
      <h1>{copy.title}</h1>
      <p>{copy.description}</p>
      <Link className="refresh-button" href="/">{copy.back}</Link>
    </div>
  );
}
