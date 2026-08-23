export interface Application {
  id: string;
  name: string;
  description: string;
  keyHint: string;
  createdAt: string;
  updatedAt: string;
}

export interface ApplicationWithKey {
  application: Application;
  apiKey: string;
}

export interface ApplicationInput {
  name: string;
  description?: string;
}

export interface ApplicationUpdate {
  name?: string;
  description?: string;
}
