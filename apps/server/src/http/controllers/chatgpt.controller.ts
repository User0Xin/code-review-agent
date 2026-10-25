import { Body, Controller, Delete, Get, Header, Inject, Param, Post } from "@nestjs/common";
import { chatgptLoginInputSchema } from "@review/contracts";
import { ChatGptAuthService } from "../../integrations/chatgpt-auth.js";

@Controller("api/chatgpt")
export class ChatGptController {
  constructor(@Inject(ChatGptAuthService) private readonly chatgpt: ChatGptAuthService) {}

  @Get("accounts")
  accounts() {
    return this.chatgpt.accounts();
  }

  @Post("login")
  @Header("Cache-Control", "no-store")
  login(@Body() body: unknown) {
    return this.chatgpt.startLogin(chatgptLoginInputSchema.parse(body).accountId);
  }

  @Get("login/:id")
  @Header("Cache-Control", "no-store")
  loginStatus(@Param("id") id: string) {
    return this.chatgpt.loginStatus(id);
  }

  @Delete("login/:id")
  cancelLogin(@Param("id") id: string) {
    return this.chatgpt.cancelLogin(id);
  }

  @Get("accounts/:id/models")
  models(@Param("id") id: string) {
    return this.chatgpt.models(id);
  }

  @Delete("accounts/:id/session")
  signOut(@Param("id") id: string) {
    return this.chatgpt.signOut(id);
  }
}
