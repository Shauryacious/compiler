package vexriscv.plugin

import spinal.core._
import spinal.lib._
import vexriscv.{Stageable, DecoderService, VexRiscv}

class SphereSurfaceAreaPlugin extends Plugin[VexRiscv] {
  object SPHERE_SURFACE_AREA_PLUGIN extends Stageable(UInt(32 bits))
  object IS_SPHERE_SURFACE_AREA_PLUGIN extends Stageable(Bool)

  override def setup(pipeline: VexRiscv): Unit = {
    import pipeline.config._
    val decoderService = pipeline.service(classOf[DecoderService])

    decoderService.add(
      key = M"0000001----------000-----0110011", // Custom instruction encoding
      List(
        IS_SPHERE_SURFACE_AREA_PLUGIN -> True,
        REGFILE_WRITE_VALID -> True,
        BYPASSABLE_EXECUTE_STAGE -> False
      )
    )
  }

  override def build(pipeline: VexRiscv): Unit = {
    import pipeline._
    import pipeline.config._

    execute plug new Area {
      import execute._

      val radius = input(SRC1).asUInt
      val pi_approx = U(314, 32 bits) // π ≈ 3.14 * 100
      val radius_squared = radius * radius
      val result = (U(4, 32 bits) * pi_approx * radius_squared) / U(100, 32 bits)

      insert(SPHERE_SURFACE_AREA_PLUGIN) := result.resized
    }

    memory plug new Area {
      import memory._

      when(arbitration.isValid && input(IS_SPHERE_SURFACE_AREA_PLUGIN)) {
        output(REGFILE_WRITE_DATA) := input(SPHERE_SURFACE_AREA_PLUGIN).asBits
      }
    }
  }
}
