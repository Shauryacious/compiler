package vexriscv.plugin

import vexriscv._
import spinal.core._
import spinal.lib._


class SingleInstructionLimiterPlugin() extends Plugin[VexRiscv] {
  override def build(pipeline: VexRiscv): Unit = {
    import pipeline._
    import pipeline.config._
    val fetcher = pipeline.service(classOf[IBusFetcher])
    when(fetcher.incoming() || List(decode,execute,memory,writeBack).map(_.arbitration.isValid).orR) {
      fetcher.haltIt()
    }
  }
}
