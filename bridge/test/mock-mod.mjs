// Sobe o núcleo do mod com o mundo falso (core/ MockServerMain) para testes.
import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const coreDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../core");

export function startMock(port, token) {
  return new Promise((resolve, reject) => {
    const win = process.platform === "win32";
    const gradlew = path.join(coreDir, win ? "gradlew.bat" : "gradlew");
    const proc = spawn(gradlew, ["-q", "--no-daemon", "runMock", `--args=${port} ${token}`], {
      cwd: coreDir,
      stdio: ["ignore", "pipe", "inherit"],
      shell: win,
      // Grupo próprio: matar só o gradlew deixaria a JVM do mock órfã.
      detached: !win,
    });
    const timer = setTimeout(() => reject(new Error("mock não subiu em 120s")), 120000);
    proc.stdout.on("data", (d) => {
      if (String(d).includes("mock pronto")) {
        clearTimeout(timer);
        resolve(proc);
      }
    });
    proc.on("exit", (code) => reject(new Error("mock saiu com código " + code)));
  });
}

export function stopMock(proc) {
  if (process.platform === "win32") {
    spawn("taskkill", ["/pid", String(proc.pid), "/T", "/F"]);
  } else {
    try {
      process.kill(-proc.pid, "SIGTERM");
    } catch {
      /* já saiu */
    }
  }
}
