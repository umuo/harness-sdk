import { AdminLogin } from "../../components/admin-login";
import { ApplicationsManager } from "../../components/applications-manager";
import {
  adminAuthenticationConfigured,
  isCurrentAdmin,
} from "../../lib/admin-auth";
import { applicationStore } from "../../lib/application-store";
import { currentLocale } from "../../lib/current-locale";
import { dictionary } from "../../lib/i18n";

export const dynamic = "force-dynamic";

export default async function ApplicationsPage() {
  const locale = await currentLocale();
  const copy = dictionary(locale).applications;
  const authenticated = await isCurrentAdmin();

  if (!authenticated) {
    return (
      <AdminLogin
        copy={{
          title: copy.adminTitle,
          description: copy.adminDescription,
          adminKey: copy.adminKey,
          signIn: copy.signIn,
          signingIn: copy.signingIn,
          requestFailed: copy.requestFailed,
        }}
      />
    );
  }

  return (
    <ApplicationsManager
      initialApplications={await applicationStore.list()}
      locale={locale}
      showLogout={adminAuthenticationConfigured()}
      copy={copy}
    />
  );
}
