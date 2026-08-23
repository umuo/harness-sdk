import {
  createHash,
  randomBytes,
  randomUUID,
  timingSafeEqual,
} from "node:crypto";
import { promises as fs } from "node:fs";
import path from "node:path";
import type {
  Application,
  ApplicationInput,
  ApplicationUpdate,
  ApplicationWithKey,
} from "./application-types";
import { platformDataDirectory } from "./data-directory";

interface StoredApplication extends Application {
  keyHash: string;
}

const STORE_VERSION = 1;
const MAX_NAME_CHARACTERS = 120;
const MAX_DESCRIPTION_CHARACTERS = 2_000;

export class ApplicationStoreError extends Error {
  constructor(
    public readonly code: "NOT_FOUND" | "CONFLICT" | "VALIDATION",
    message: string,
  ) {
    super(message);
    this.name = "ApplicationStoreError";
  }
}

class LocalApplicationStore {
  private readonly file: string;
  private writeChain: Promise<void> = Promise.resolve();

  constructor() {
    this.file = path.join(
      platformDataDirectory(),
      "_platform",
      "applications.json",
    );
  }

  async list(): Promise<Application[]> {
    await this.writeChain;
    return (await this.read()).map(publicApplication);
  }

  async get(id: string): Promise<Application | null> {
    await this.writeChain;
    const found = (await this.read()).find((application) => application.id === id);
    return found ? publicApplication(found) : null;
  }

  create(input: ApplicationInput): Promise<ApplicationWithKey> {
    return this.write(async () => {
      const applications = await this.read();
      const name = validatedName(input.name);
      ensureUniqueName(applications, name);
      const description = validatedDescription(input.description ?? "");
      const id = `app_${randomUUID().replace(/-/g, "")}`;
      const apiKey = generateApiKey(id);
      const now = new Date().toISOString();
      const application: StoredApplication = {
        id,
        name,
        description,
        keyHint: keyHint(apiKey),
        keyHash: hash(apiKey),
        createdAt: now,
        updatedAt: now,
      };
      applications.push(application);
      await this.persist(applications);
      return { application: publicApplication(application), apiKey };
    });
  }

  update(id: string, update: ApplicationUpdate): Promise<Application> {
    return this.write(async () => {
      const applications = await this.read();
      const index = applications.findIndex((application) => application.id === id);
      if (index < 0) throw notFound(id);
      if (update.name === undefined && update.description === undefined) {
        throw new ApplicationStoreError(
          "VALIDATION",
          "At least one of name or description is required",
        );
      }
      const current = applications[index];
      const name = update.name === undefined
        ? current.name
        : validatedName(update.name);
      ensureUniqueName(applications, name, id);
      const description = update.description === undefined
        ? current.description
        : validatedDescription(update.description);
      const updated: StoredApplication = {
        ...current,
        name,
        description,
        updatedAt: new Date().toISOString(),
      };
      applications[index] = updated;
      await this.persist(applications);
      return publicApplication(updated);
    });
  }

  delete(id: string): Promise<boolean> {
    return this.write(async () => {
      const applications = await this.read();
      const retained = applications.filter((application) => application.id !== id);
      if (retained.length === applications.length) return false;
      await this.persist(retained);
      return true;
    });
  }

  rotateKey(id: string): Promise<ApplicationWithKey> {
    return this.write(async () => {
      const applications = await this.read();
      const index = applications.findIndex((application) => application.id === id);
      if (index < 0) throw notFound(id);
      const apiKey = generateApiKey(id);
      const updated: StoredApplication = {
        ...applications[index],
        keyHint: keyHint(apiKey),
        keyHash: hash(apiKey),
        updatedAt: new Date().toISOString(),
      };
      applications[index] = updated;
      await this.persist(applications);
      return { application: publicApplication(updated), apiKey };
    });
  }

  async authenticate(apiKey: string): Promise<Application | null> {
    if (!apiKey || apiKey.length > 512) return null;
    await this.writeChain;
    const candidate = Buffer.from(hash(apiKey), "hex");
    for (const application of await this.read()) {
      const expected = Buffer.from(application.keyHash, "hex");
      if (
        candidate.length === expected.length &&
        timingSafeEqual(candidate, expected)
      ) {
        return publicApplication(application);
      }
    }
    return null;
  }

  private write<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.writeChain.then(operation);
    this.writeChain = result.then(() => undefined, () => undefined);
    return result;
  }

  private async read(): Promise<StoredApplication[]> {
    try {
      const content = JSON.parse(await fs.readFile(this.file, "utf8")) as {
        version?: unknown;
        applications?: unknown;
      };
      if (content.version !== STORE_VERSION || !Array.isArray(content.applications)) {
        throw new Error("Unsupported or invalid application store format");
      }
      return content.applications.map(parseStoredApplication);
    } catch (error) {
      if (isMissing(error)) return [];
      throw error;
    }
  }

  private async persist(applications: StoredApplication[]): Promise<void> {
    const directory = path.dirname(this.file);
    await fs.mkdir(directory, { recursive: true });
    const temporary = `${this.file}.${randomBytes(8).toString("hex")}.tmp`;
    try {
      await fs.writeFile(
        temporary,
        JSON.stringify({ version: STORE_VERSION, applications }),
        { encoding: "utf8", mode: 0o600 },
      );
      await fs.rename(temporary, this.file);
    } finally {
      await fs.rm(temporary, { force: true });
    }
  }
}

function parseStoredApplication(input: unknown): StoredApplication {
  if (!isRecord(input)) throw new Error("Invalid application store entry");
  const application: StoredApplication = {
    id: requiredStoredText(input.id, "id"),
    name: requiredStoredText(input.name, "name"),
    description: optionalStoredText(input.description, "description"),
    keyHint: requiredStoredText(input.keyHint, "keyHint"),
    keyHash: requiredStoredText(input.keyHash, "keyHash"),
    createdAt: requiredStoredText(input.createdAt, "createdAt"),
    updatedAt: requiredStoredText(input.updatedAt, "updatedAt"),
  };
  if (!/^[0-9a-f]{64}$/.test(application.keyHash)) {
    throw new Error("Invalid application key hash");
  }
  return application;
}

function publicApplication(application: StoredApplication): Application {
  return {
    id: application.id,
    name: application.name,
    description: application.description,
    keyHint: application.keyHint,
    createdAt: application.createdAt,
    updatedAt: application.updatedAt,
  };
}

function validatedName(value: unknown): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new ApplicationStoreError("VALIDATION", "Application name is required");
  }
  const name = value.trim();
  if (name.length > MAX_NAME_CHARACTERS) {
    throw new ApplicationStoreError(
      "VALIDATION",
      `Application name exceeds ${MAX_NAME_CHARACTERS} characters`,
    );
  }
  return name;
}

function validatedDescription(value: unknown): string {
  if (typeof value !== "string") {
    throw new ApplicationStoreError(
      "VALIDATION",
      "Application description must be a string",
    );
  }
  const description = value.trim();
  if (description.length > MAX_DESCRIPTION_CHARACTERS) {
    throw new ApplicationStoreError(
      "VALIDATION",
      `Application description exceeds ${MAX_DESCRIPTION_CHARACTERS} characters`,
    );
  }
  return description;
}

function ensureUniqueName(
  applications: StoredApplication[],
  name: string,
  excludedId?: string,
) {
  if (
    applications.some(
      (application) =>
        application.id !== excludedId &&
        application.name.toLocaleLowerCase() === name.toLocaleLowerCase(),
    )
  ) {
    throw new ApplicationStoreError(
      "CONFLICT",
      `Application name already exists: ${name}`,
    );
  }
}

function generateApiKey(applicationId: string): string {
  return `aoh_${applicationId}_${randomBytes(32).toString("base64url")}`;
}

function keyHint(apiKey: string): string {
  return `${apiKey.slice(0, 16)}…${apiKey.slice(-6)}`;
}

function hash(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

function notFound(id: string) {
  return new ApplicationStoreError("NOT_FOUND", `Application not found: ${id}`);
}

function requiredStoredText(value: unknown, name: string): string {
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`Invalid application ${name}`);
  }
  return value;
}

function optionalStoredText(value: unknown, name: string): string {
  if (value === undefined || value === null) return "";
  if (typeof value !== "string") throw new Error(`Invalid application ${name}`);
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isMissing(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "code" in error &&
    (error as { code?: string }).code === "ENOENT"
  );
}

export const applicationStore = new LocalApplicationStore();
