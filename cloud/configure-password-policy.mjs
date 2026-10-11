import { initializeApp, applicationDefault, deleteApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

export const passwordPolicy = {
  enforcementState: 'ENFORCE', forceUpgradeOnSignin: false,
  constraints: { minLength: 8, maxLength: 8, requireNumeric: true, requireLowercase: false, requireUppercase: false, requireNonAlphanumeric: false }
};
export async function updatePasswordPolicy(manager, project, backup) {
  const previous = await manager.getProjectConfig();
  await writeFile(backup, JSON.stringify({ project, passwordPolicyConfig: previous.passwordPolicyConfig ?? null }, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  return await manager.updateProjectConfig({ passwordPolicyConfig: passwordPolicy });
}
export async function configurePasswordPolicy(project, backup) {
  if (!/^[a-z][a-z0-9-]{4,62}$/.test(project)) throw new Error('Nieprawidłowy projekt.');
  const app = initializeApp({ credential: applicationDefault(), projectId: project }, `password-policy-${Date.now()}`);
  try {
    return await updatePasswordPolicy(getAuth(app).projectConfigManager(), project, backup);
  } finally { await deleteApp(app); }
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const { values } = parseArgs({ options: { project: { type: 'string' }, backup: { type: 'string', default: `polityka-hasel-${Date.now()}.json` } } });
    await configurePasswordPolicy(values.project, values.backup);
    console.log('Polityka Firebase: dokładnie 8 znaków, co najmniej jedna cyfra, również przy resetowaniu hasła. Kopia poprzedniej polityki została zapisana.');
  } catch { console.error('Nie udało się ustawić polityki haseł. Sprawdź logowanie administracyjne i uprawnienia Firebase Authentication. Konta i dane aplikacji nie były zmieniane przez ten skrypt.'); process.exitCode = 1; }
}
