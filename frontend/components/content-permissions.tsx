"use client";

export const contentPermissions = [
  { key: "READ_PRIVATE", label: "查看草稿" },
  { key: "WRITE_PRIVATE", label: "修改草稿" },
  { key: "PUBLISH", label: "发布，并修改已发布的内容" },
] as const;

export function PermissionFields({
  value,
  onChange,
  disabled,
  label,
}: {
  value: string[];
  onChange: (value: string[]) => void;
  disabled?: boolean;
  label: string;
}) {
  return (
    <fieldset disabled={disabled}>
      <legend>{label}</legend>
      <div className="capabilities">
        {contentPermissions.map((permission) => (
          <label className="capability" key={permission.key}>
            <input
              type="checkbox"
              checked={value.includes(permission.key)}
              onChange={(event) => {
                const next = new Set(value);
                if (event.target.checked) {
                  next.add(permission.key);
                  if (permission.key === "WRITE_PRIVATE")
                    next.add("READ_PRIVATE");
                } else {
                  next.delete(permission.key);
                  if (permission.key === "READ_PRIVATE")
                    next.delete("WRITE_PRIVATE");
                }
                onChange([...next]);
              }}
            />
            <span>{permission.label}</span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}

export function permissionSummary(value: string[]) {
  return (
    contentPermissions
      .filter((permission) => value.includes(permission.key))
      .map((permission) => permission.label)
      .join("、") || "只能查看已发布的内容"
  );
}
