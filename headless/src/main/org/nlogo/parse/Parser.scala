// (C) Uri Wilensky. https://github.com/NetLogo/NetLogo

package org.nlogo.parse

import org.nlogo.{ api, nvm }
import org.nlogo.util.Femto

object Parser extends Parser {

  // tokenizer singletons
  val Tokenizer2D =
    Femto.scalaSingleton(classOf[api.TokenizerInterface],
      "org.nlogo.lex.Tokenizer2D")
  val Tokenizer3D =
    Femto.scalaSingleton(classOf[api.TokenizerInterface],
      "org.nlogo.lex.Tokenizer3D")
  val TokenMapper2D =
    Femto.scalaSingleton(classOf[api.TokenMapperInterface],
      "org.nlogo.lex.TokenMapper2D")

}

trait Parser extends nvm.ParserInterface {

  type ProceduresMap = nvm.CompilerInterface.ProceduresMap

  private def tokenizer(is3D: Boolean) =
    if(is3D) Parser.Tokenizer3D else Parser.Tokenizer2D

  // entry points

  def frontEnd(source: String, oldProcedures: ProceduresMap = nvm.CompilerInterface.NoProcedures, program: api.Program = api.Program.empty()): (Seq[ProcedureDefinition], StructureParser.Results) =
    frontEndHelper(source, None, program, true,
      oldProcedures, new api.DummyExtensionManager, frontEndOnly = true)

  // used by Tortoise. bails after parsing so we can put a different back end on.
  // the frontEndOnly flag is currently just for Tortoise and can hopefully go away in the future.
  // Tortoise currently needs SetVisitor to happen even though SetVisitor is technically part of the
  // back end.  An example of how this might be redone in the future would be to fold the
  // functionality of SetVisitor into IdentifierParser. - ST 1/24/13
  def frontEndHelper(source: String, displayName: Option[String], program: api.Program, subprogram: Boolean,
      oldProcedures: ProceduresMap, extensionManager: api.ExtensionManager, frontEndOnly: Boolean = false)
    : (Seq[ProcedureDefinition], StructureParser.Results) = {
    val structureResults = StructureParser.parseAll(
      if (program.is3D) Parser.Tokenizer3D else Parser.Tokenizer2D,
      source, displayName, program, subprogram, oldProcedures, extensionManager)
    val taskNumbers = Iterator.from(1)
    // the return type is plural because tasks inside a procedure get
    // lambda-lifted and become top level procedures
    def parseProcedure(procedure: nvm.Procedure): Seq[ProcedureDefinition] = {
      val rawTokens = structureResults.tokens(procedure)
      new LetScoper(procedure, rawTokens, structureResults.program.usedNames ++ (structureResults.procedures.keys ++ oldProcedures.keys).map(_ -> "procedure")).scan()
      val iP =
        new IdentifierParser(structureResults.program, oldProcedures, structureResults.procedures, extensionManager, false)
      val identifiedTokens =
        iP.process(rawTokens.iterator, procedure)  // resolve references
      new ExpressionParser(procedure, taskNumbers)
        .parse(identifiedTokens) // parse
    }
    val procDefs = structureResults.procedures.values.flatMap(parseProcedure).toVector
    if (frontEndOnly)  // for Tortoise
      for(procdef <- procDefs)
        procdef.accept(new SetVisitor)
    (procDefs, structureResults)
  }

  // these two used by input boxes
  def checkCommandSyntax(source: String, program: api.Program, procedures: ProceduresMap, extensionManager: api.ExtensionManager, parse: Boolean) {
    checkSyntax("to __bogus-name " + source + "\nend",
                true, program, procedures, extensionManager, parse)
  }
  def checkReporterSyntax(source: String, program: api.Program, procedures: ProceduresMap, extensionManager: api.ExtensionManager, parse: Boolean) {
    checkSyntax("to-report __bogus-name report " + source + "\nend",
                true, program, procedures, extensionManager, parse)
  }

  // like in the auto-converter we want to compile as far as we can but
  // we assume that any tokens we don't recognize are actually globals
  // that we don't know about.
  private def checkSyntax(source: String, subprogram: Boolean, program: api.Program, oldProcedures: ProceduresMap, extensionManager: api.ExtensionManager, parse: Boolean) {
    val results = new StructureParser(tokenizer(program.is3D).tokenizeRobustly(source), None,
                                      StructureParser.Results(program, oldProcedures))
      .parse(subprogram)
    val identifierParser = new IdentifierParser(program, nvm.CompilerInterface.NoProcedures, results.procedures, extensionManager, !parse)
    for(procedure <- results.procedures.values) {
      val tokens = identifierParser.process(results.tokens(procedure).iterator, procedure)
      if(parse)
        new ExpressionParser(procedure).parse(tokens)
    }
  }

  def autoConvert(source: String, subprogram: Boolean, reporter: Boolean, version: String, w: AnyRef, ignoreErrors: Boolean, is3D: Boolean): String = {
    // well, this typecast is gruesome, but I really want to put CompilerInterface in
    // api not nvm, and since AutoConverter2 is a grotesque hack anyway and can probably
    // go away after 4.1, we'll just do it... - ST 2/23/09
    val workspace = w.asInstanceOf[nvm.Workspace]
    // AutoConverter1 handles the easy conversions
    val result1 = new AutoConverter1(tokenizer(is3D)).convert(source, subprogram, reporter, version)
    // AutoConverter2 handles the hard ones that require parsing
    new AutoConverter2(workspace, ignoreErrors, tokenizer(is3D))
      .convert(result1, subprogram, reporter, version)
  }

  ///

  /// TODO: There are a few places below where we downcast api.World to agent.World in order to pass
  /// it to LiteralParser.  This should really be cleaned up so that LiteralParser uses api.World
  /// too. - ST 2/23/09

  // In the following 3 methods, the initial call to NumberParser is a performance optimization.
  // During import-world, we're calling readFromString over and over again and most of the time
  // the result is a number.  So we try the fast path through NumberParser first before falling
  // back to the slow path where we actually tokenize. - ST 4/7/11

  def readFromString(source: String, is3D: Boolean): AnyRef =
    api.NumberParser.parse(source).right.getOrElse(
      new LiteralParser().getLiteralValue(tokenizer(is3D).tokenize(source).iterator))

  def readFromString(source: String, world: api.World, extensionManager: api.ExtensionManager, is3D: Boolean): AnyRef =
    api.NumberParser.parse(source).right.getOrElse(
      new LiteralParser(world.asInstanceOf[org.nlogo.agent.World], extensionManager)
        .getLiteralValue(tokenizer(is3D).tokenize(source).iterator))

  def readNumberFromString(source: String, world: api.World, extensionManager: api.ExtensionManager, is3D: Boolean): java.lang.Double =
    api.NumberParser.parse(source).right.getOrElse(
      new LiteralParser(world.asInstanceOf[org.nlogo.agent.World], extensionManager)
      .getNumberValue(tokenizer(is3D).tokenize(source).iterator))

  @throws(classOf[java.io.IOException])
  def readFromFile(currFile: api.File, world: api.World, extensionManager: api.ExtensionManager): AnyRef = {
    val tokens: Iterator[api.Token] =
      Femto.get(classOf[api.TokenReaderInterface], "org.nlogo.lex.TokenReader",
                Array(currFile, tokenizer(world.program.is3D)))
    val result = new LiteralParser(world.asInstanceOf[org.nlogo.agent.World], extensionManager)
      .getLiteralFromFile(tokens)
    // now skip whitespace, so that the model can use file-at-end? to see whether there are any
    // more values left - ST 2/18/04
    // org.nlogo.util.File requires us to maintain currFile.pos ourselves -- yuck!!! - ST 8/5/04
    var done = false
    while(!done) {
      currFile.reader.mark(1)
      currFile.pos += 1
      val i = currFile.reader.read()
      if(i == -1 || !Character.isWhitespace(i)) {
        currFile.reader.reset()
        currFile.pos -= 1
        done = true
      }
    }
    result
  }

  // used for procedures menu
  def findProcedurePositions(source: String, is3D: Boolean): Map[String, (String, Int, Int, Int)] =
    new StructureParserExtras(tokenizer(is3D)).findProcedurePositions(source)

  def getCompletions(source: String, name: String, is3D: Boolean): Seq[(String, String)] = {
    tokenizer(is3D).allReportersAndCommands.map { prim => (prim.toLowerCase, "system") } ++
    new StructureParserExtras(tokenizer(is3D)).getAllProcedures(source).map { prim => (prim.toLowerCase, "user") }
  }

  // used for includes menu
  def findIncludes(sourceFileName: String, source: String, is3D: Boolean): Map[String, String] =
    new StructureParserExtras(tokenizer(is3D)).findIncludes(sourceFileName, source)

  // used by VariableNameEditor
  def isValidIdentifier(s: String, is3D: Boolean) = tokenizer(is3D).isValidIdentifier(s)

  // used by CommandLine
  def isReporter(s: String, program: api.Program, procedures: ProceduresMap, extensionManager: api.ExtensionManager) =
    try {
      val results =
        new StructureParser(tokenizer(program.is3D).tokenize("to __is-reporter? report " + s + "\nend"),
                            None, StructureParser.Results(program, procedures))
          .parse(subprogram = true)
      val identifierParser =
        new IdentifierParser(program, procedures, results.procedures, extensionManager, forgiving = false)
      val proc = results.procedures.values.head
      val tokens = identifierParser.process(results.tokens(proc).iterator, proc)
      tokens
        .tail  // skip _report
        .map(_.tpe)
        .dropWhile(_ == api.TokenType.OPEN_PAREN)
        .headOption
        .exists(reporterTokenTypes)
    }
    catch { case _: api.CompilerException => false }

  private val reporterTokenTypes: Set[api.TokenType] = {
    import api.TokenType._
    Set(OPEN_BRACKET, CONSTANT, IDENT, REPORTER, VARIABLE)
  }

  // used by the indenter. we always use the 2D tokenizer since it doesn't matter in this context
  def getTokenAtPosition(source: String, position: Int): api.Token =
    tokenizer(false).getTokenAtPosition(source, position)

  // this is for the syntax-highlighting editor
  def tokenizeForColorization(source: String, extensionManager: api.ExtensionManager, is3D: Boolean): Seq[api.Token] =
    tokenizer(is3D).tokenizeForColorization(source, extensionManager)

}
