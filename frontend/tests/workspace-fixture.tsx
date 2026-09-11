import React from "react";
import type { Root } from "react-dom/client";
import { WorkspaceProvider } from "../components/workspace-context";

export const workspaceId = "11111111-1111-4111-8111-111111111111";
export function scopedRoot(root: Root): Root {
  return {
    render: (node) =>
      root.render(
        <WorkspaceProvider workspaceId={workspaceId}>{node}</WorkspaceProvider>,
      ),
    unmount: () => root.unmount(),
  };
}
