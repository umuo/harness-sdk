import path from "node:path";

export function platformDataDirectory(): string {
  const configuredDirectory = process.env.AGENT_OBSERVABILITY_DATA_DIR;
  return configuredDirectory
    ? path.resolve(/* turbopackIgnore: true */ configuredDirectory)
    : path.join(process.cwd(), ".data");
}
