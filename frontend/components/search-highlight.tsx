export function SearchHighlight({
  text,
  query,
}: {
  text: string;
  query?: string;
}) {
  if (!query || query.length > 200) return <>{text}</>;
  const parts = text.split(query);
  return (
    <>
      {parts.map((part, index) => (
        <span key={index}>
          {index > 0 && <mark>{query}</mark>}
          {part}
        </span>
      ))}
    </>
  );
}
