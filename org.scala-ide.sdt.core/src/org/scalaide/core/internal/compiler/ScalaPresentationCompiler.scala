package org.scalaide.core.internal.compiler

import scala.tools.nsc.interactive.FreshRunReq
import scala.collection.concurrent
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.SynchronizedMap
import org.eclipse.jdt.core.compiler.IProblem
import org.eclipse.jdt.internal.compiler.problem.DefaultProblem
import org.eclipse.jdt.internal.compiler.problem.ProblemSeverities
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.interactive.InteractiveReporter
import scala.tools.nsc.interactive.Problem
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.io.VirtualFile
import scala.tools.nsc.reporters.Reporter
import scala.reflect.internal.util.BatchSourceFile
import scala.reflect.internal.util.Position
import scala.reflect.internal.util.SourceFile
import org.scalaide.core.completion.CompletionContext
import org.scalaide.core.internal.jdt.search.ScalaIndexBuilder
import org.scalaide.core.internal.jdt.model.ScalaJavaMapper
import org.scalaide.core.internal.jdt.search.ScalaMatchLocator
import org.scalaide.core.internal.jdt.model.ScalaStructureBuilder
import org.scalaide.ui.internal.jdt.model.ScalaOverrideIndicatorBuilder
import org.scalaide.core.resources.EclipseFile
import org.scalaide.core.resources.EclipseResource
import org.scalaide.logging.HasLogger
import scala.tools.nsc.util.FailedInterrupt
import scala.tools.nsc.symtab.Flags
import org.scalaide.core.completion.CompletionProposal
import org.eclipse.jdt.core.IMethod
import scala.tools.nsc.interactive.MissingResponse
import org.scalaide.core.internal.jdt.model.ScalaSourceFile
import org.scalaide.core.extensions.SourceFileProviderRegistry
import org.eclipse.core.runtime.Path
import org.eclipse.core.resources.IFile
import org.eclipse.jdt.internal.core.util.Util
import org.scalaide.core.IScalaProject
import org.scalaide.core.IScalaPlugin
import org.scalaide.util.ScalaWordFinder
import scalariform.lexer.{ScalaLexer, ScalaLexerException}
import scala.reflect.internal.util.RangePosition
import org.scalaide.core.internal.jdt.model.ScalaStructureBuilder
import org.scalaide.core.compiler.IScalaPresentationCompiler.Implicits._
import org.scalaide.core.compiler._
import org.scalaide.core.compiler.IScalaPresentationCompiler._
import scala.tools.nsc.interactive.InteractiveReporter
import scala.tools.nsc.interactive.CommentPreservingTypers
import org.scalaide.ui.internal.editor.hover.ScalaDocHtmlProducer
import scala.util.Try
import scala.reflect.internal.util.NoPosition
import org.eclipse.jface.text.IRegion
import org.eclipse.jdt.core.IJavaProject
import org.eclipse.jface.text.hyperlink.IHyperlink
import org.scalaide.core.internal.hyperlink.ScalaHyperlink

class ScalaPresentationCompiler(name: String, _settings: Settings) extends {
  /*
   * Lock object for protecting compiler names. Names are cached in a global `Array[Char]`
   * and concurrent access may lead to overwritten names.
   *
   * @note This field is EARLY because `newTermName` is hit during construction of the superclass `Global`,
   *       and the lock object has to be constructed already.
   */
  private val nameLock = new Object

} with Global(_settings, new ScalaPresentationCompiler.PresentationReporter, name)
  with ScaladocGlobalCompatibilityTrait
  with ScalaStructureBuilder
  with ScalaIndexBuilder
  with ScalaMatchLocator
  with ScalaOverrideIndicatorBuilder
  with ScalaJavaMapper
  with JavaSig
  with LocateSymbol
  with CompilerApiExtensions
  with IScalaPresentationCompiler
  with HasLogger
  with Scaladoc { self =>

  override lazy val analyzer = new {
    val global: ScalaPresentationCompiler.this.type = ScalaPresentationCompiler.this
  } with InteractiveScaladocAnalyzer with CommentPreservingTypers

  override def forScaladoc = true

  def presentationReporter = reporter.asInstanceOf[ScalaPresentationCompiler.PresentationReporter]
  presentationReporter.compiler = this

  def compilationUnits: List[InteractiveCompilationUnit] = {
    val managedFiles = unitOfFile.keySet.toList
    for {
      f <- managedFiles.collect { case ef: EclipseFile => ef }
      icu <- SourceFileProviderRegistry.getProvider(f.workspacePath).createFrom(f.workspacePath)
      if icu.exists
    } yield icu
  }

  /** Ask to reload all units managed by this presentation compiler */
  @deprecated("use askReloadManagedUnits instead", "4.0.0")
  def reconcileOpenUnits() = askReloadManagedUnits()

  def askReloadManagedUnits() {
    askReload(compilationUnits)
  }

  /**
   * The set of compilation units to be reloaded at the next refresh round.
   * Refresh rounds can be triggered by the reconciler, but also interactive requests
   * (e.g. completion)
   */
  private val scheduledUnits = new scala.collection.mutable.HashMap[InteractiveCompilationUnit, SourceFile]

  /**
   * Add a compilation unit (CU) to the set of CUs to be Reloaded at the next refresh round.
   */
  def scheduleReload(icu : InteractiveCompilationUnit, srcFile: SourceFile) : Unit = {
    scheduledUnits.synchronized { scheduledUnits += ((icu, srcFile)) }
  }

  /**
   * Reload the scheduled compilation units and reset the set of scheduled reloads.
   *  For any CU unknown by the compiler at reload, this is a no-op.
   */
  def flushScheduledReloads(): Response[Unit] = {
    val res = new Response[Unit]
    scheduledUnits.synchronized {
      val reloadees = scheduledUnits.filter{(scu:(InteractiveCompilationUnit, SourceFile)) => compilationUnits.contains(scu._1)}.toList

      if (reloadees.isEmpty) res.set(())
      else {
        val reloadFiles = reloadees map { case (_, srcFile) => srcFile }
        askReload(reloadFiles, res)
        logger.info(s"Flushed ${reloadees.mkString("", ",", "")}")
      }
      scheduledUnits.clear()
    }
    res.get
    res
  }

  override def askFilesDeleted(sources: List[SourceFile], response: Response[Unit]): Unit = {
    flushScheduledReloads()
    super.askFilesDeleted(sources, response)
  }

  override def askLinkPos(sym: Symbol, source: SourceFile, response: Response[Position]): Unit = {
    flushScheduledReloads()
    super.askLinkPos(sym, source, response)
  }

  override def askParsedEntered(source: SourceFile, keepLoaded: Boolean, response: Response[Tree]): Unit = {
    flushScheduledReloads()
    super.askParsedEntered(source, keepLoaded, response)
  }

  override def askScopeCompletion(pos: Position, response: Response[List[Member]]): Unit = {
    flushScheduledReloads()
    super.askScopeCompletion(pos, response)
  }

  override def askToDoFirst(source: SourceFile): Unit = {
    flushScheduledReloads()
    super.askToDoFirst(source)
  }

  override def askTypeAt(pos: Position, response: Response[Tree]): Unit = {
    flushScheduledReloads()
    super.askTypeAt(pos, response)
  }

  override def askTypeCompletion(pos: Position, response: Response[List[Member]]): Unit = {
    flushScheduledReloads()
    super.askTypeCompletion(pos, response)
  }

  override def askLoadedTyped(sourceFile: SourceFile, keepLoaded: Boolean, response: Response[Tree]): Unit = {
    flushScheduledReloads()
    super.askLoadedTyped(sourceFile, keepLoaded, response)
  }

  override def askStructure(sourceFile: SourceFile, keepLoaded: Boolean): Response[Tree] = {
    withResponse[Tree](askStructure(keepLoaded)(sourceFile, _))
  }

  def problemsOf(file: AbstractFile): List[ScalaCompilationProblem] = {
    unitOfFile get file match {
      case Some(unit) =>
        askLoadedTyped(unit.source, false).get
        unit.problems.toList flatMap presentationReporter.eclipseProblem
      case None =>
        logger.info("Missing unit for file %s when retrieving errors. Errors will not be shown in this file".format(file))
        logger.info(unitOfFile.toString)
        Nil
    }
  }

  def problemsOf(scu: InteractiveCompilationUnit): List[ScalaCompilationProblem] = problemsOf(scu.file)

  @deprecated("Use loadedType instead.", "4.0.0")
  def body(sourceFile: SourceFile, keepLoaded: Boolean = false): Either[Tree, Throwable] = loadedType(sourceFile, keepLoaded)

  def loadedType(sourceFile: SourceFile, keepLoaded: Boolean = false): Either[Tree, Throwable] = {
    if (self.onCompilerThread)
      throw ScalaPresentationCompiler.InvalidThread("Tried to execute `askLoadedType` while inside `ask`")
    askLoadedTyped(sourceFile, keepLoaded).get
  }

  /** Perform `op` on the compiler thread. This method returns a `Response` that may
   *  never complete (there is no default timeout). In very rare cases, the current presentation compiler
   *  might restart and miss to complete a pending request. Clients should always specify
   *  a timeout value when awaiting on a future returned by this method.
   */
  def asyncExec[A](op: => A): Response[A] = {
    askForResponse(() => op)
  }

  /** Ask with a default timeout. Keep around for compatibility with the m2 release. */
  @deprecated("Use asyncExec instead", "4.0.0")
  def askOption[A](op: () => A): Option[A] = askOption(op, 10000)

  @deprecated("Use asyncExec instead", "4.0.0")
  def askOption[A](op: () => A, timeout: Int): Option[A] = {
    import scala.concurrent.duration._
    asyncExec(op()).getOption(timeout.millis)
  }

  /** Returns a `Response` containing doc comment information for a given symbol.
   *
   *  @param   sym        The symbol whose doc comment should be retrieved (might come from a classfile)
   *  @param   source     The source file that's supposed to contain the definition
   *  @param   site       The symbol where 'sym' is observed
   *  @param   fragments  All symbols that can contribute to the generated documentation
   *                      together with their source files.
   *  @return   response   A response that will be set to the following:
   *                      If `source` contains a definition of a given symbol that has a doc comment,
   *                      the (expanded, raw, position) triplet for a comment, otherwise ("", "", NoPosition).
   *  Note: This operation does not automatically load sources that are not yet loaded.
   */
  def asyncDocComment(sym: Symbol, source: SourceFile, site: Symbol, fragments: List[(Symbol, SourceFile)]): Response[(String, String, Position)] = {
    val response = new Response[(String, String, Position)]
    // we can only ensure sym.owner is a class after lambda-lifting, but to be past lambda-lifting, we need to retype, which on hovers is heavy
    if (asyncExec { sym.owner.isClass || sym.owner.isFreeType }.getOrElse(false)()) {
      askDocComment(sym, source, site, fragments, response)
    } else {
      response.set("", "", NoPosition)
    }
    response
  }

  /** Ask to put scu in the beginning of the list of files to be typechecked.
   *
   *  If the file has not been 'reloaded' first, it does nothing.
   */
  def askToDoFirst(scu: InteractiveCompilationUnit) {
    askToDoFirst(scu.lastSourceMap().sourceFile)
  }

  /** Reload the given compilation unit. If the unit is not tracked by the presentation
   *  compiler, it will be from now on.
   */
  def askReload(scu: InteractiveCompilationUnit, source: SourceFile): Response[Unit] = {
    withResponse[Unit] { res => askReload(List(source), res) }
  }

  /** Atomically load a list of units in the current presentation compiler. */
  def askReload(units: List[InteractiveCompilationUnit]): Response[Unit] = {
    withResponse[Unit] { res => askReload(units.map(_.lastSourceMap().sourceFile), res) }
  }

  /** Make sure we remove the doc comments we accumulated so far. */
  override def askReload(sources: List[SourceFile], response: Response[Unit]) = {
    logger.info(s"Clearing doc comments (${cookedDocComments.size} entries)")
    clearDocComments();
    super.askReload(sources, response)
  }

  def filesDeleted(units: Seq[InteractiveCompilationUnit]) {
    logger.info("files deleted:\n" + (units map (_.file.path) mkString "\n"))
    if (!units.isEmpty)
      askFilesDeleted(units.map(_.lastSourceMap().sourceFile).toList)
  }

  def discardCompilationUnit(scu: InteractiveCompilationUnit): Unit = {
    logger.info("discarding " + scu.file.path)
    asyncExec { removeUnitOf(scu.lastSourceMap().sourceFile) }.getOption()
  }

  /** Tell the presentation compiler to refresh the given files,
   *  if they are not managed by the presentation compiler already.
   */
  def refreshChangedFiles(files: List[IFile]) {
    // transform to batch source files
    val freshSources = files.collect {
      // When a compilation unit is moved (e.g. using the Move refactoring) between packages,
      // an ElementChangedEvent is fired but with the old IFile name. Ignoring the file does
      // not seem to cause any bad effects later on, so we simply ignore these files -- Mirko
      // using an Util class from jdt.internal to read the file, Eclipse doesn't seem to
      // provide an API way to do it -- Luc
      case file if file.exists => new BatchSourceFile(EclipseResource(file), Util.getResourceContentsAsCharArray(file))
    }

    // only the files not already managed should be refreshed
    val managedFiles = unitOfFile.keySet
    val notLoadedFiles = freshSources.filter(f => !managedFiles(f.file))

    notLoadedFiles.foreach(file => {
      // call askParsedEntered to force the refresh without loading the file
      val r = withResponse[Tree] { askParsedEntered(file, false, _) }

      r.get
    })

    // reconcile the opened editors if some files have been refreshed
    if (notLoadedFiles.nonEmpty)
      askReloadManagedUnits()
  }

  override def synchronizeNames = true

  override def logError(msg: String, t: Throwable) =
    eclipseLog.error(msg, t)

  def destroy() {
    logger.info("shutting down presentation compiler on project: " + name)
    askShutdown()
  }

  /** Add a new completion proposal to the buffer. Skip constructors and accessors.
   *
   *  Computes a very basic relevance metric based on where the symbol comes from
   *  (in decreasing order of relevance):
   *    - members defined by the owner
   *    - inherited members
   *    - members added by views
   *    - packages
   *    - members coming from Any/AnyRef/Object
   *
   *  TODO We should have a more refined strategy based on the context (inside an import, case
   *       pattern, 'new' call, etc.)
   */
  def mkCompletionProposal(prefix: Array[Char], start: Int, sym: Symbol, tpe: Type,
    inherited: Boolean, viaView: Symbol, context: CompletionContext, project: IScalaProject): CompletionProposal = {

    /** Some strings need to be enclosed in back-ticks to be usable as identifiers in scala
     *  source. This function adds the back-ticks to a given identifier, if necessary.
     */
    def addBackTicksIfNecessary(identifier: String): String = {
      def needsBackTicks(identifier: String) = {
        import scalariform.lexer.Tokens._

        try {
          val tokens = ScalaLexer.tokenise(identifier)  // The last token is always EOF
          tokens.size match {
            case 1 => true    // whitespace
            case 2 => !(IDS contains tokens.head.tokenType)
            case more => true
          }
        } catch {case _: ScalaLexerException => true  /* Illegal chars encountered */}
      }

      if(needsBackTicks(identifier)) s"`$identifier`" else identifier
    }

    import org.scalaide.core.completion.MemberKind._

    val kind = if (sym.isSourceMethod && !sym.hasFlag(Flags.ACCESSOR | Flags.PARAMACCESSOR)) Def
    else if (sym.hasPackageFlag) Package
    else if (sym.isClass) Class
    else if (sym.isTrait) Trait
    else if (sym.isPackageObject) PackageObject
    else if (sym.isModule) Object
    else if (sym.isType) Type
    else Val

    val name = if (sym.isConstructor)
      sym.owner.decodedName
    else if (sym.hasGetter)
      (sym.getter: Symbol).decodedName
    else sym.decodedName

    val signature =
      if (sym.isMethod) {
        declPrinter.defString(sym, flagMask = 0L, showKind = false)(tpe)
      } else name
    val container = sym.owner.enclClass.fullName

    // rudimentary relevance, place own members before inherited ones, and before view-provided ones
    var relevance = 100
    if (!sym.isLocalToBlock) relevance -= 10 // non-local symbols are less relevant than local ones
    if (inherited) relevance -= 10
    if (viaView != NoSymbol) relevance -= 20
    if (sym.hasPackageFlag) relevance -= 30
    // theoretically we'd need an 'ask' around this code, but given that
    // Any and AnyRef are definitely loaded, we call directly to definitions.
    if (sym.owner == definitions.AnyClass
      || sym.owner == definitions.AnyRefClass
      || sym.owner == definitions.ObjectClass) {
      relevance -= 40
    }
    val casePenalty = if (name.take(prefix.length) != prefix.mkString) 50 else 0
    relevance -= casePenalty

    val namesAndTypes = for {
      section <- tpe.paramss
      if section.isEmpty || !section.head.isImplicit
    } yield for (param <- section) yield (param.name.toString, param.tpe.toString)

    val (scalaParamNames, paramTypes) = namesAndTypes.map(_.unzip).unzip

    // we save this value to make sure it's evaluated in the PC thread
    // the closure below can be evaluated in any thread
    val isJavaMethod = sym.isJavaDefined && sym.isMethod
    val getParamNames = () => {
      if (isJavaMethod) {
        getJavaElement(sym, project.javaProject) collect {
          case method: IMethod => List(method.getParameterNames.toList)
        } getOrElse scalaParamNames
      } else scalaParamNames
    }
    val docFun = () => {
      val comment = parsedDocComment(sym, sym.enclClass, project.javaProject)
      val header = headerForSymbol(sym, tpe)
      if (comment.isDefined) (new ScalaDocHtmlProducer).getBrowserInput(this)(comment.get, sym, header.getOrElse("")) else None
    }

    CompletionProposal(
      kind,
      context,
      start,
      addBackTicksIfNecessary(name),
      signature,
      container,
      relevance,
      sym.isJavaDefined,
      getParamNames,
      paramTypes,
      sym.fullName,
      false,
      docFun)
  }

  def mkHyperlink(sym: Symbol, name: String, region: IRegion, javaProject: IJavaProject, label: Symbol => String = defaultHyperlinkLabel _): Option[IHyperlink] = {
    asyncExec {
      findDeclaration(sym, javaProject) map {
        case (f, pos) =>
          new ScalaHyperlink(openableOrUnit = f,
              pos = f.lastSourceMap().originalPos(pos),
              len = sym.name.decodedName.length,
              label = label(sym),
              text = name,
              wordRegion = region)
      }
    }.getOrElse(None)()
  }

  private [core] def defaultHyperlinkLabel(sym: Symbol): String = s"${sym.kindString} ${sym.fullName}"

  override def inform(msg: String): Unit =
    logger.debug("[%s]: %s".format(name, msg))
}

object ScalaPresentationCompiler {
  case class InvalidThread(msg: String) extends RuntimeException(msg)

  def defaultScalaSettings(errorFn: String => Unit = Console.println): Settings = new Settings(errorFn)

  class PresentationReporter extends InteractiveReporter {
    var compiler: ScalaPresentationCompiler = null

    def nscSeverityToEclipse(severityLevel: Int) =
      severityLevel match {
        case ERROR.id   => ProblemSeverities.Error
        case WARNING.id => ProblemSeverities.Warning
        case INFO.id    => ProblemSeverities.Ignore
      }

    def eclipseProblem(prob: Problem): Option[ScalaCompilationProblem] = {
      import prob._
      if (pos.isDefined) {
        val source = pos.source
        val reducedPos: Option[Position]=
          if (pos.isRange)
            Some(toSingleLine(pos))
          else{
            val wordPos = Try(ScalaWordFinder.findWord(source.content, pos.start).getLength).toOption
            wordPos map ((p) => new RangePosition(pos.source, pos.point, pos.point, pos.point + p))
          }

        reducedPos flatMap { reducedPos =>
          val fileName =
            source.file match {
              case EclipseFile(file) =>
                Some(file.getFullPath().toString)
              case vf: VirtualFile =>
                Some(vf.path)
              case _ =>
                None
            }
          fileName.map(ScalaCompilationProblem(
            _,
            nscSeverityToEclipse(severityLevel),
            formatMessage(msg),
            reducedPos.start,
            math.max(reducedPos.start, reducedPos.end - 1),
            reducedPos.line,
            reducedPos.column))
        }
      } else None
    }

    /** Original code from Position.toSingleLine, copied since it was removed from 2.11 */
    def toSingleLine(pos: Position): Position = pos.source match {
      case bs: BatchSourceFile if pos.end > 0 && bs.offsetToLine(pos.start) < bs.offsetToLine(pos.end - 1) =>
        val pointLine = bs.offsetToLine(pos.point)
        new RangePosition(pos.source, pos.start, pos.point, bs.lineToOffset(pointLine + 1))
      case _ => pos
    }

    def formatMessage(msg: String) = msg.map {
      case '\n' | '\r' => ' '
      case c           => c
    }
  }
}
