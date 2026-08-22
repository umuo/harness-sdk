import { cookies } from "next/headers";
import { LOCALE_COOKIE, type Locale } from "./i18n";

/** Chinese is the intentional default, independent of browser preferences. */
export async function currentLocale(): Promise<Locale> {
  const value = (await cookies()).get(LOCALE_COOKIE)?.value;
  return value === "en" ? "en" : "zh-CN";
}
