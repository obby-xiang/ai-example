## Preferences
- Communication language: Chinese
- Development approach: Provide business scenarios and non-negotiable constraints, leaving technical implementation details to the assistant
- Values reliability in instruction parsing and structured tool calling
- Tool definition location: All tool definitions must be placed in the backend, even for frontend tools; frontend only传递 tool names
- AI workflow architecture: Prefers backend runtime with agent loop, frontend tools use pause-resume mechanism
- Implementation priority: Focus on end-to-end verification and UI interaction validation

## Tech Stack
- Backend: Spring Boot, JDK
- Frontend: Vue3 (Composition API)
- AI: OpenAI specification compatible interfaces
