"use client";

export const contentPermissions = [
  { key: "READ_PRIVATE", label: "读取私密内容" },
  { key: "WRITE_PRIVATE", label: "修改私密内容" },
  { key: "PUBLISH", label: "修改和发布公开内容" },
] as const;

export function PermissionFields({ value, onChange, disabled, label }: {
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
            <input type="checkbox" checked={value.includes(permission.key)} onChange={(event) => {
              const next = new Set(value);
              if (event.target.checked) {
                next.add(permission.key);
                if (permission.key === "WRITE_PRIVATE") next.add("READ_PRIVATE");
              } else {
                next.delete(permission.key);
                if (permission.key === "READ_PRIVATE") next.delete("WRITE_PRIVATE");
              }
              onChange([...next]);
            }} />
            <span>{permission.label}</span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}

export function permissionSummary(value: string[]) {
  return contentPermissions.filter((permission) => value.includes(permission.key))
    .map((permission) => permission.label).join("、") || "仅查看公开目录";
}
