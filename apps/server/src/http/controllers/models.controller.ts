import { Body, Controller, Delete, Inject, Param, Patch, Post } from "@nestjs/common";
import { modelInputSchema } from "@review/contracts";
import { ModelService } from "../../configuration/model.service.js";

@Controller("api/models")
export class ModelsController {
  constructor(@Inject(ModelService) private readonly models: ModelService) {}

  @Post()
  create(@Body() body: unknown) {
    return this.models.save(modelInputSchema.parse(body));
  }

  @Patch(":id")
  update(@Param("id") id: string, @Body() body: unknown) {
    return this.models.update(id, modelInputSchema.parse(body));
  }

  @Delete(":id")
  remove(@Param("id") id: string) {
    return this.models.remove(id);
  }

  @Post(":id/test")
  test(@Param("id") id: string) {
    return this.models.test(id);
  }
}
