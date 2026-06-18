import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

afterEach(() => {
  cleanup();
});

class WebGLRenderingContextStub {}
class WebGL2RenderingContextStub {}

Object.defineProperty(globalThis, "WebGLRenderingContext", {
  value: WebGLRenderingContextStub,
  writable: true
});

Object.defineProperty(globalThis, "WebGL2RenderingContext", {
  value: WebGL2RenderingContextStub,
  writable: true
});
