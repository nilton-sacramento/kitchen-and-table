// Ambiente exclusivamente descartável. Não lê DB_URL e não usa o banco operacional.
import fs from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";
import { spawn } from "node:child_process";
import readline from "node:readline";
const root = path.resolve(
  ".tools",
  "entrega-2-4",
  "ambiente-" + crypto.randomUUID(),
);
await fs.mkdir(root, { recursive: true });
const report = await fs.readFile(
  "target/surefire-reports/TEST-com.kitchentable.funcionarios.FuncionariosPostgresTest.xml",
  "utf8",
);
const raw = report.match(
  /<property name="java.class.path" value="([^"]+)"/,
)?.[1];
if (!raw) throw Error("Execute mvn verify antes de iniciar.");
const classpath = raw
  .replaceAll("&quot;", '"')
  .replaceAll("&apos;", "'")
  .replaceAll("&amp;", "&")
  .replaceAll("&lt;", "<")
  .replaceAll("&gt;", ">");
const c = {
  email: "proprietario-" + crypto.randomUUID() + "@example.com",
  password: crypto.randomUUID(),
  managerEmail: "gerente-" + crypto.randomUUID() + "@example.com",
  managerPassword: crypto.randomUUID(),
  pin: String(crypto.randomInt(1000000)).padStart(6, "0"),
  secondPin: String(crypto.randomInt(1000000)).padStart(6, "0"),
  atendimento: true,
};
const java = spawn(
  "java",
  [
    "-cp",
    classpath,
    "com.kitchentable.funcionarios.FuncionariosBrowserFixture",
  ],
  { windowsHide: true, stdio: ["pipe", "pipe", "pipe"] },
);
java.stderr.on("data", () => {});
java.on("error", () =>
  console.error("Java não iniciou; confira Java 17 e o build."),
);
const lines = readline.createInterface({ input: java.stdout });
let ready = false;
const timer = setTimeout(() => {
  if (!ready) {
    console.error(
      "Ambiente não iniciou no prazo. Confira Docker e execute mvn verify.",
    );
    stop();
  }
}, 120000);
lines.on("line", async (line) => {
  if (!line.startsWith("KT_BROWSER_READY ")) return;
  ready = true;
  clearTimeout(timer);
  const d = JSON.parse(line.slice(17));
  const file = path.join(root, "ACESSOS-PRIVADOS.md");
  await fs.writeFile(
    file,
    [
      "# Ambiente descartável 2.4 — NÃO PUBLICAR",
      d.url,
      "Proprietário: " + c.email,
      "Senha: " + c.password,
      "Unidade A: " + d.estabelecimentoId,
      "Unidade B: " + d.destinoId,
      "Atendente A: " + d.atendente,
      "PIN atendente (A e B): " + c.pin,
      "Caixa A: " + d.caixa,
      "PIN caixa: " + c.secondPin,
      "Atendente B: " + d.atendenteOutraUnidade,
      "",
      "Use /operacional.html após sair do acesso administrativo.",
      "Ctrl+C no terminal encerra somente esta fixture descartável.",
    ].join("\n"),
    "utf8",
  );
  console.log("Sistema: " + d.url);
  console.log("Acesso operacional: " + d.url + "/operacional.html");
  console.log("Credenciais somente no arquivo local: " + file);
  console.log("PostgreSQL 16 exclusivo desta fixture; Ctrl+C para encerrar.");
});
function stop() {
  clearTimeout(timer);
  if (java.exitCode === null) {
    java.stdin.write("\n");
    java.stdin.end();
  }
}
process.on("SIGINT", stop);
process.on("SIGTERM", stop);
java.on("exit", (code) => {
  clearTimeout(timer);
  lines.close();
  process.exitCode = code || 0;
});
java.stdin.write(JSON.stringify(c) + "\n");
