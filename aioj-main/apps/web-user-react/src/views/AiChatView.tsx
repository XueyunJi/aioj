import { AiTutorWorkspace } from "../components/AiTutorWorkspace";

export function AiChatView() {
  return (
    <div className="mx-auto flex max-w-[1500px] flex-col px-4 py-5 md:px-8">
      <AiTutorWorkspace source="ai_tutor" />
    </div>
  );
}
