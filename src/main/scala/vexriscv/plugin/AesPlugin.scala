package vexriscv.plugin

import spinal.core._
import spinal.lib._
import vexriscv.{DecoderService, Stageable, VexRiscv}

/**
  * The AesPlugin allow to reduce the instruction count of each AES round by providing the following instruction :
  * 1) aes_enc_round(rs1, rs2, sel).      rd = rs1 ^ quad_mul(sel, sbox(byte_sel(rs2, sel)))
  * 2) aes_enc_round_last(rs1, rs2, sel). rd = rs1 ^ quad_sbox(byte_sel(rs2, sel))
  * 3) aes_dec_round(rs1, rs2, sel).      rd = rs1 ^ quad_inv_sbox(quad_mul(sel,byte_sel(rs2, sel)))
  * 4) aes_dec_round_last(rs1, rs2, sel). rd = rs1 ^ quad_inv_sbox(byte_sel(rs2, sel))
  *
  * Here is what those inner functions mean:
  * - sbox apply the sbox transformation on the 'sel' byte of the 32 bits word
  * - quad_mul multiply (Galois field) each byte of 32 bits word by a constant (which depend of sel)
  * - quad_inv_sbox apply the inverse sbox transformation on each byte of 32 bits word
  *
  * You can find a complet example of those instruction usage in aes_cusom.h in vexriscv_aes_encrypt and
  * vexriscv_aes_decrypt. Those function are made to work on little endian as in the linux kernel default AES
  * implementation, but unlike libressl, libopenssl and dropbear ones (swapping the byte of the expended key can fix that).
  *
  * This plugin implement the processing using a single 32_bits * 512_words rom to fetch the sbox/inv_sbox/multiplication
  * results already combined. This rom is formated as following :
  *
  * From word 0x000 to 0x0FF, it is formatted as follow :  (note multiplication are in Galois field)
  * [ 7 :  0] : SBox[word_address & 0xFF] * 1
  * [15 :  8] : SBox[word_address & 0xFF] * 2
  * [23 : 16] : SBox[word_address & 0xFF] * 3
  * [31 : 24] : inverse SBox[word_address & 0xFF] * 1 (Used for the last round of the decryption)
  *
  * From word 0x100 to 0x1FF, it is formatted as follow :
  * [ 7 :  0] : inverse SBox[word_address & 0xFF * 14]
  * [15 :  8] : inverse SBox[word_address & 0xFF *  9]
  * [23 : 16] : inverse SBox[word_address & 0xFF * 13]
  * [31 : 24] : inverse SBox[word_address & 0xFF * 11]
  *
  * So, on each instruction, the following is done (in order)
  * 1) Select the 'sel' byte of RS2
  * 2) Read the rom at a address which depend of the RS2 selected byte and the instruction
  * 3) Permute the rom read data depending the instruction and the 'sel' argument
  * 4) Xor the result with RS1 and return that as instruction result
  *
  * The instructions are encoded by default as following :
  * --SS-LDXXXXXYYYYY000ZZZZZ0001011
  *
  * Where :
  * - XXXXX is the register file source 2 (RS2)
  * - YYYYY is the register file source 1 (RS1)
  * - ZZZZZ is the register file destination
  * - D=1 mean decrypt, D=0 mean encrypt
  * - L=1 mean last round, L=0 mean full round
  * - SS specify which byte should be used from RS2 for the processing
  *
  * In practice the aes-256-cbc performances should improve by a factor 4. See the following results from libopenssl
  * from a SoC running linux at 100 MHz
  *   type                 16 bytes     64 bytes    256 bytes   1024 bytes   8192 bytes  16384 bytes
  *   aes-256-cbc SW         492.58k      700.22k      796.41k      831.49k      830.09k      832.81k
  *   aes-256 cbc HW        1781.52k     2834.07k     3323.07k     3486.72k     3465.22k     3440.10k
  */

case class AesPlugin(encoding : MaskedLiteral = M"-----------------000-----0001011") extends Plugin[VexRiscv]{

  object IS_AES extends Stageable(Bool)
  object CALC extends Stageable(Bits(32 bits))

  val mapping = new {
    def DECRYPT = 25     // 0/1 =>  encrypt/decrypt
    def LAST_ROUND = 26
    def BYTE_SEL = 28    //Which byte should be used in RS2
  }

  //Callback to setup the plugin and ask for different services
  override def setup(pipeline: VexRiscv): Unit = {
    import pipeline.config._

    val decoderService = pipeline.service(classOf[DecoderService])

    decoderService.addDefault(IS_AES, False)
    decoderService.add(
      key = encoding,
      List(
        IS_AES              -> True,
        REGFILE_WRITE_VALID      -> True,
        BYPASSABLE_EXECUTE_STAGE -> False,
        BYPASSABLE_MEMORY_STAGE  -> False, //Late result
        RS1_USE                  -> True,
        RS2_USE                  -> True
      )
    )
  }

  override def build(pipeline: VexRiscv): Unit = {
    import pipeline._
    import pipeline.config._



    def BANK0 = (TE0, SBOX_INV).zipped.map((te0, inv) => (te0.toLong) | (inv.toLong << 24))
    def BANK1 =  TD0



    val onExecute = execute plug new Area{
      import execute._
      val byteSel = input(INSTRUCTION)(mapping.BYTE_SEL, 2 bits).asUInt
      val bankSel = input(INSTRUCTION)(mapping.DECRYPT) && !input(INSTRUCTION)(mapping.LAST_ROUND)
      val romAddress = U(bankSel ## input(RS2).subdivideIn(8 bits).read(byteSel))
    }

    memory plug new Area{
      import memory._

      //Decode the rom data
      val rom = new Area {
        val storage = Mem(Bits(32 bits), 512) initBigInt((BANK0 ++ BANK1).map(BigInt(_)))

        val data = storage.readSync(onExecute.romAddress, !arbitration.isStuck)
        val bytes = data.subdivideIn(8 bits)

        def VecUInt(l: Int*) = Vec(l.map(U(_, 2 bits)))
        // remap will be used to decode the rom
        val remap = Vec(
          VecUInt(2, 0, 0, 1),
          VecUInt(0, 0, 0, 0),
          VecUInt(3, 2, 1, 0),
          VecUInt(3, 3, 3, 3)
        )

        val address = U(input(INSTRUCTION)(mapping.DECRYPT) ## input(INSTRUCTION)(mapping.LAST_ROUND))
        val output = remap(address)
      }
      
      val wordDesuffle = new Area{
        val zero = B"0000"
        val byteSel = input(INSTRUCTION)(mapping.BYTE_SEL, 2 bits).asUInt
        val output = Vec(Bits(8 bits), 4)

        def remap(l : Int*) = Vec(l.map(rom.output(_)))
        val sel = byteSel.mux(
          0 -> remap(3, 2, 1, 0),
          1 -> remap(0, 3, 2, 1),
          2 -> remap(1, 0, 3, 2),
          3 -> remap(2, 1, 0, 3)
        )
        when(input(INSTRUCTION)(mapping.LAST_ROUND)){
          zero := B"1111"
          zero(byteSel) := False
        }

        //Finaly, mux the rom data
        for(byteId <- 0 to 3){
          output(byteId) := rom.bytes(sel(byteId))
          when(zero(byteId)){
            output(byteId) := 0
          }
        }
      }

      val xored = wordDesuffle.output.asBits ^ input(RS1)
      insert(CALC) := xored
    }

    writeBack plug new Area {
      import writeBack._

      when(input(IS_AES)) {
        output(REGFILE_WRITE_DATA) := input(CALC)
      }
    }
  }

  // Encryption table which solve a single byte sbox + column mix. Used for all rounds
  def TE0 = List(
    0xa5c663, 0x84f87c, 0x99ee77, 0x8df67b,
    0x0dfff2, 0xbdd66b, 0xb1de6f, 0x5491c5,
    0x506030, 0x030201, 0xa9ce67, 0x7d562b,
    0x19e7fe, 0x62b5d7, 0xe64dab, 0x9aec76,
    0x458fca, 0x9d1f82, 0x4089c9, 0x87fa7d,
    0x15effa, 0xebb259, 0xc98e47, 0x0bfbf0,
    0xec41ad, 0x67b3d4, 0xfd5fa2, 0xea45af,
    0xbf239c, 0xf753a4, 0x96e472, 0x5b9bc0,
    0xc275b7, 0x1ce1fd, 0xae3d93, 0x6a4c26,
    0x5a6c36, 0x417e3f, 0x02f5f7, 0x4f83cc,
    0x5c6834, 0xf451a5, 0x34d1e5, 0x08f9f1,
    0x93e271, 0x73abd8, 0x536231, 0x3f2a15,
    0x0c0804, 0x5295c7, 0x654623, 0x5e9dc3,
    0x283018, 0xa13796, 0x0f0a05, 0xb52f9a,
    0x090e07, 0x362412, 0x9b1b80, 0x3ddfe2,
    0x26cdeb, 0x694e27, 0xcd7fb2, 0x9fea75,
    0x1b1209, 0x9e1d83, 0x74582c, 0x2e341a,
    0x2d361b, 0xb2dc6e, 0xeeb45a, 0xfb5ba0,
    0xf6a452, 0x4d763b, 0x61b7d6, 0xce7db3,
    0x7b5229, 0x3edde3, 0x715e2f, 0x971384,
    0xf5a653, 0x68b9d1, 0x000000, 0x2cc1ed,
    0x604020, 0x1fe3fc, 0xc879b1, 0xedb65b,
    0xbed46a, 0x468dcb, 0xd967be, 0x4b7239,
    0xde944a, 0xd4984c, 0xe8b058, 0x4a85cf,
    0x6bbbd0, 0x2ac5ef, 0xe54faa, 0x16edfb,
    0xc58643, 0xd79a4d, 0x556633, 0x941185,
    0xcf8a45, 0x10e9f9, 0x060402, 0x81fe7f,
    0xf0a050, 0x44783c, 0xba259f, 0xe34ba8,
    0xf3a251, 0xfe5da3, 0xc08040, 0x8a058f,
    0xad3f92, 0xbc219d, 0x487038, 0x04f1f5,
    0xdf63bc, 0xc177b6, 0x75afda, 0x634221,
    0x302010, 0x1ae5ff, 0x0efdf3, 0x6dbfd2,
    0x4c81cd, 0x14180c, 0x352613, 0x2fc3ec,
    0xe1be5f, 0xa23597, 0xcc8844, 0x392e17,
    0x5793c4, 0xf255a7, 0x82fc7e, 0x477a3d,
    0xacc864, 0xe7ba5d, 0x2b3219, 0x95e673,
    0xa0c060, 0x981981, 0xd19e4f, 0x7fa3dc,
    0x664422, 0x7e542a, 0xab3b90, 0x830b88,
    0xca8c46, 0x29c7ee, 0xd36bb8, 0x3c2814,
    0x79a7de, 0xe2bc5e, 0x1d160b, 0x76addb,
    0x3bdbe0, 0x566432, 0x4e743a, 0x1e140a,
    0xdb9249, 0x0a0c06, 0x6c4824, 0xe4b85c,
    0x5d9fc2, 0x6ebdd3, 0xef43ac, 0xa6c462,
    0xa83991, 0xa43195, 0x37d3e4, 0x8bf279,
    0x32d5e7, 0x438bc8, 0x596e37, 0xb7da6d,
    0x8c018d, 0x64b1d5, 0xd29c4e, 0xe049a9,
    0xb4d86c, 0xfaac56, 0x07f3f4, 0x25cfea,
    0xafca65, 0x8ef47a, 0xe947ae, 0x181008,
    0xd56fba, 0x88f078, 0x6f4a25, 0x725c2e,
    0x24381c, 0xf157a6, 0xc773b4, 0x5197c6,
    0x23cbe8, 0x7ca1dd, 0x9ce874, 0x213e1f,
    0xdd964b, 0xdc61bd, 0x860d8b, 0x850f8a,
    0x90e070, 0x427c3e, 0xc471b5, 0xaacc66,
    0xd89048, 0x050603, 0x01f7f6, 0x121c0e,
    0xa3c261, 0x5f6a35, 0xf9ae57, 0xd069b9,
    0x911786, 0x5899c1, 0x273a1d, 0xb9279e,
    0x38d9e1, 0x13ebf8, 0xb32b98, 0x332211,
    0xbbd269, 0x70a9d9, 0x89078e, 0xa73394,
    0xb62d9b, 0x223c1e, 0x921587, 0x20c9e9,
    0x4987ce, 0xffaa55, 0x785028, 0x7aa5df,
    0x8f038c, 0xf859a1, 0x800989, 0x171a0d,
    0xda65bf, 0x31d7e6, 0xc68442, 0xb8d068,
    0xc38241, 0xb02999, 0x775a2d, 0x111e0f,
    0xcb7bb0, 0xfca854, 0xd66dbb, 0x3a2c16
  )


  // Decryption table which solve a single byte sbox + column mix. Not used in the last round
  def TD0 = List(
    0x50a7f451l, 0x5365417el, 0xc3a4171al, 0x965e273al,
    0xcb6bab3bl, 0xf1459d1fl, 0xab58faacl, 0x9303e34bl,
    0x55fa3020l, 0xf66d76adl, 0x9176cc88l, 0x254c02f5l,
    0xfcd7e54fl, 0xd7cb2ac5l, 0x80443526l, 0x8fa362b5l,
    0x495ab1del, 0x671bba25l, 0x980eea45l, 0xe1c0fe5dl,
    0x02752fc3l, 0x12f04c81l, 0xa397468dl, 0xc6f9d36bl,
    0xe75f8f03l, 0x959c9215l, 0xeb7a6dbfl, 0xda595295l,
    0x2d83bed4l, 0xd3217458l, 0x2969e049l, 0x44c8c98el,
    0x6a89c275l, 0x78798ef4l, 0x6b3e5899l, 0xdd71b927l,
    0xb64fe1bel, 0x17ad88f0l, 0x66ac20c9l, 0xb43ace7dl,
    0x184adf63l, 0x82311ae5l, 0x60335197l, 0x457f5362l,
    0xe07764b1l, 0x84ae6bbbl, 0x1ca081fel, 0x942b08f9l,
    0x58684870l, 0x19fd458fl, 0x876cde94l, 0xb7f87b52l,
    0x23d373abl, 0xe2024b72l, 0x578f1fe3l, 0x2aab5566l,
    0x0728ebb2l, 0x03c2b52fl, 0x9a7bc586l, 0xa50837d3l,
    0xf2872830l, 0xb2a5bf23l, 0xba6a0302l, 0x5c8216edl,
    0x2b1ccf8al, 0x92b479a7l, 0xf0f207f3l, 0xa1e2694el,
    0xcdf4da65l, 0xd5be0506l, 0x1f6234d1l, 0x8afea6c4l,
    0x9d532e34l, 0xa055f3a2l, 0x32e18a05l, 0x75ebf6a4l,
    0x39ec830bl, 0xaaef6040l, 0x069f715el, 0x51106ebdl,
    0xf98a213el, 0x3d06dd96l, 0xae053eddl, 0x46bde64dl,
    0xb58d5491l, 0x055dc471l, 0x6fd40604l, 0xff155060l,
    0x24fb9819l, 0x97e9bdd6l, 0xcc434089l, 0x779ed967l,
    0xbd42e8b0l, 0x888b8907l, 0x385b19e7l, 0xdbeec879l,
    0x470a7ca1l, 0xe90f427cl, 0xc91e84f8l, 0x00000000l,
    0x83868009l, 0x48ed2b32l, 0xac70111el, 0x4e725a6cl,
    0xfbff0efdl, 0x5638850fl, 0x1ed5ae3dl, 0x27392d36l,
    0x64d90f0al, 0x21a65c68l, 0xd1545b9bl, 0x3a2e3624l,
    0xb1670a0cl, 0x0fe75793l, 0xd296eeb4l, 0x9e919b1bl,
    0x4fc5c080l, 0xa220dc61l, 0x694b775al, 0x161a121cl,
    0x0aba93e2l, 0xe52aa0c0l, 0x43e0223cl, 0x1d171b12l,
    0x0b0d090el, 0xadc78bf2l, 0xb9a8b62dl, 0xc8a91e14l,
    0x8519f157l, 0x4c0775afl, 0xbbdd99eel, 0xfd607fa3l,
    0x9f2601f7l, 0xbcf5725cl, 0xc53b6644l, 0x347efb5bl,
    0x7629438bl, 0xdcc623cbl, 0x68fcedb6l, 0x63f1e4b8l,
    0xcadc31d7l, 0x10856342l, 0x40229713l, 0x2011c684l,
    0x7d244a85l, 0xf83dbbd2l, 0x1132f9ael, 0x6da129c7l,
    0x4b2f9e1dl, 0xf330b2dcl, 0xec52860dl, 0xd0e3c177l,
    0x6c16b32bl, 0x99b970a9l, 0xfa489411l, 0x2264e947l,
    0xc48cfca8l, 0x1a3ff0a0l, 0xd82c7d56l, 0xef903322l,
    0xc74e4987l, 0xc1d138d9l, 0xfea2ca8cl, 0x360bd498l,
    0xcf81f5a6l, 0x28de7aa5l, 0x268eb7dal, 0xa4bfad3fl,
    0xe49d3a2cl, 0x0d927850l, 0x9bcc5f6al, 0x62467e54l,
    0xc2138df6l, 0xe8b8d890l, 0x5ef7392el, 0xf5afc382l,
    0xbe805d9fl, 0x7c93d069l, 0xa92dd56fl, 0xb31225cfl,
    0x3b99acc8l, 0xa77d1810l, 0x6e639ce8l, 0x7bbb3bdbl,
    0x097826cdl, 0xf418596el, 0x01b79aecl, 0xa89a4f83l,
    0x656e95e6l, 0x7ee6ffaal, 0x08cfbc21l, 0xe6e815efl,
    0xd99be7bal, 0xce366f4al, 0xd4099feal, 0xd67cb029l,
    0xafb2a431l, 0x31233f2al, 0x3094a5c6l, 0xc066a235l,
    0x37bc4e74l, 0xa6ca82fcl, 0xb0d090e0l, 0x15d8a733l,
    0x4a9804f1l, 0xf7daec41l, 0x0e50cd7fl, 0x2ff69117l,
    0x8dd64d76l, 0x4db0ef43l, 0x544daaccl, 0xdf0496e4l,
    0xe3b5d19el, 0x1b886a4cl, 0xb81f2cc1l, 0x7f516546l,
    0x04ea5e9dl, 0x5d358c01l, 0x737487fal, 0x2e410bfbl,
    0x5a1d67b3l, 0x52d2db92l, 0x335610e9l, 0x1347d66dl,
    0x8c61d79al, 0x7a0ca137l, 0x8e14f859l, 0x893c13ebl,
    0xee27a9cel, 0x35c961b7l, 0xede51ce1l, 0x3cb1477al,
    0x59dfd29cl, 0x3f73f255l, 0x79ce1418l, 0xbf37c773l,
    0xeacdf753l, 0x5baafd5fl, 0x146f3ddfl, 0x86db4478l,
    0x81f3afcal, 0x3ec468b9l, 0x2c342438l, 0x5f40a3c2l,
    0x72c31d16l, 0x0c25e2bcl, 0x8b493c28l, 0x41950dffl,
    0x7101a839l, 0xdeb30c08l, 0x9ce4b4d8l, 0x90c15664l,
    0x6184cb7bl, 0x70b632d5l, 0x745c6c48l, 0x4257b8d0l
  )

  // Last round decryption sbox
  def SBOX_INV = List(
    0x52, 0x09, 0x6a, 0xd5, 0x30, 0x36, 0xa5, 0x38, 0xbf, 0x40, 0xa3, 0x9e, 0x81, 0xf3, 0xd7, 0xfb,
    0x7c, 0xe3, 0x39, 0x82, 0x9b, 0x2f, 0xff, 0x87, 0x34, 0x8e, 0x43, 0x44, 0xc4, 0xde, 0xe9, 0xcb,
    0x54, 0x7b, 0x94, 0x32, 0xa6, 0xc2, 0x23, 0x3d, 0xee, 0x4c, 0x95, 0x0b, 0x42, 0xfa, 0xc3, 0x4e,
    0x08, 0x2e, 0xa1, 0x66, 0x28, 0xd9, 0x24, 0xb2, 0x76, 0x5b, 0xa2, 0x49, 0x6d, 0x8b, 0xd1, 0x25,
    0x72, 0xf8, 0xf6, 0x64, 0x86, 0x68, 0x98, 0x16, 0xd4, 0xa4, 0x5c, 0xcc, 0x5d, 0x65, 0xb6, 0x92,
    0x6c, 0x70, 0x48, 0x50, 0xfd, 0xed, 0xb9, 0xda, 0x5e, 0x15, 0x46, 0x57, 0xa7, 0x8d, 0x9d, 0x84,
    0x90, 0xd8, 0xab, 0x00, 0x8c, 0xbc, 0xd3, 0x0a, 0xf7, 0xe4, 0x58, 0x05, 0xb8, 0xb3, 0x45, 0x06,
    0xd0, 0x2c, 0x1e, 0x8f, 0xca, 0x3f, 0x0f, 0x02, 0xc1, 0xaf, 0xbd, 0x03, 0x01, 0x13, 0x8a, 0x6b,
    0x3a, 0x91, 0x11, 0x41, 0x4f, 0x67, 0xdc, 0xea, 0x97, 0xf2, 0xcf, 0xce, 0xf0, 0xb4, 0xe6, 0x73,
    0x96, 0xac, 0x74, 0x22, 0xe7, 0xad, 0x35, 0x85, 0xe2, 0xf9, 0x37, 0xe8, 0x1c, 0x75, 0xdf, 0x6e,
    0x47, 0xf1, 0x1a, 0x71, 0x1d, 0x29, 0xc5, 0x89, 0x6f, 0xb7, 0x62, 0x0e, 0xaa, 0x18, 0xbe, 0x1b,
    0xfc, 0x56, 0x3e, 0x4b, 0xc6, 0xd2, 0x79, 0x20, 0x9a, 0xdb, 0xc0, 0xfe, 0x78, 0xcd, 0x5a, 0xf4,
    0x1f, 0xdd, 0xa8, 0x33, 0x88, 0x07, 0xc7, 0x31, 0xb1, 0x12, 0x10, 0x59, 0x27, 0x80, 0xec, 0x5f,
    0x60, 0x51, 0x7f, 0xa9, 0x19, 0xb5, 0x4a, 0x0d, 0x2d, 0xe5, 0x7a, 0x9f, 0x93, 0xc9, 0x9c, 0xef,
    0xa0, 0xe0, 0x3b, 0x4d, 0xae, 0x2a, 0xf5, 0xb0, 0xc8, 0xeb, 0xbb, 0x3c, 0x83, 0x53, 0x99, 0x61,
    0x17, 0x2b, 0x04, 0x7e, 0xba, 0x77, 0xd6, 0x26, 0xe1, 0x69, 0x14, 0x63, 0x55, 0x21, 0x0c, 0x7d
  )
}
