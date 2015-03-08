// (C) Uri Wilensky. https://github.com/NetLogo/NetLogo

package org.nlogo.workspace

import org.nlogo.agent.OutputObject
import org.nlogo.api
import org.nlogo.api.{ExtensionManager => APIExtensionManager, _}
import org.nlogo.nvm.CompilerInterface.ProceduresMap
import org.nlogo.nvm.{Reporter, CompilerResults, CompilerFlags, CompilerInterface, DummyWorkspace}
import org.scalatest.{ BeforeAndAfterAll, FunSuite, OneInstancePerTest }

import java.io.{File => JFile, BufferedReader, FileReader, PrintWriter, IOException}

class DefaultFileManagerTests extends FunSuite with OneInstancePerTest with BeforeAndAfterAll {

  override def beforeAll() = {
    AbstractWorkspace.isApplet(false) // may need to reset these after finished
    AbstractWorkspace.isApp(true)
  }

  override def afterAll() = {
    AbstractWorkspace.isApp(false)
  }

  test("openFile allows opening files that do not exist") {
    fileManager.getFile("foobar")
  }

  test("findOpenFile returns null if it has not opened the file") {
    assert(fileManager.findOpenFile("foobar").isEmpty)
  }

  test("findOpenFile returns the file if that file has been opened") {
    withTmpFile { filename =>
      fileManager.openFile(filename)
      assert(fileManager.findOpenFile(filename).isDefined)
      assert(fileManager.findOpenFile("foobar").isEmpty)
    }
  }

  test("closeCurrentFile will error when if no file is open") {
    intercept[IOException] {
      fileManager.closeCurrentFile()
    }
  }

  test("currentFile is None if no file has been opened") {
    assert(fileManager.currentFile.isEmpty)
  }

  test("initializes a file with position set to 0 and eof set to false") {
    withWrittenFile("", { file =>
      assert(file.pos == 0)
      assert(! file.eof)
    })
  }

  test("currentFile is set to the most recently opened file") {
    withTmpFile { fileA => withTmpFile { fileB =>
      fileManager.openFile(fileA)
      fileManager.openFile(fileB)
      assert(fileManager.currentFile == fileManager.findOpenFile(fileB))
    } }
  }

  test("currentFile returns None if the file has been closed") {
    withTmpFile { fileA =>
      fileManager.openFile(fileA)
      fileManager.closeCurrentFile()
      assert(fileManager.currentFile.isEmpty)
    }
  }

  test("findOpenFile will not find a file after it has been closed") {
    withTmpFile { filename =>
      fileManager.openFile(filename)
      fileManager.closeCurrentFile()
      assert(fileManager.findOpenFile(filename).isEmpty)
    }
  }

  test("fileExists returns true if the file exists") {
    withTmpFile(f => assert(fileManager.fileExists(f)))
  }

  test("fileExists returns false if the file does not exist") {
    fileManager.fileExists("foobar")
  }

  test("flushCurrentFile raises an IOException when there is no current file") {
    intercept[IOException] { fileManager.flushCurrentFile() }
  }

  test("ensureMode raises an exception if no file is open") {
    intercept[IOException](fileManager.ensureMode(FileMode.Read))
    intercept[IOException](fileManager.ensureMode(FileMode.Write))
    intercept[IOException](fileManager.ensureMode(FileMode.Append))
    intercept[IOException](fileManager.ensureMode(FileMode.None))
  }

  test("for open mode none, ensureMode raises IllegalStateException") {
    withOpenedFile {
      intercept[IllegalArgumentException](fileManager.ensureMode(FileMode.None))
    }
  }

  test("for open modes, ensureMode allows only the first type of operation requested by the file") {
    openModes.foreach { activeMode =>
      withOpenedFile {
        fileManager.ensureMode(activeMode)
        (allModes - activeMode).foreach { othermode =>
          intercept[IOException](fileManager.ensureMode(othermode))
        }
        intercept[IOException](fileManager.ensureMode(FileMode.None))
      }
    }
  }

  test("ensureMode with read mode raises a FileNotFound exception if the file does not exist") {
    openModes.foreach { activeMode =>
      fileManager.openFile("foo/barbaz")
      intercept[IOException](fileManager.ensureMode(activeMode))
    }
  }

  test("writeOutputObject raises a reasonable exception if no file is open") {
    intercept[IOException](fileManager.writeOutputObject(output))
  }

  test("writeOutputObject writes the object to the file") {
    withOpenedFile {
      fileManager.ensureMode(FileMode.Append)
      fileManager.writeOutputObject(output)
      val f = fileManager.currentFile
      fileManager.closeCurrentFile()
      fileManager.openFile(f.get.getAbsolutePath)
      assert(fileManager.readLine() == "bar")
    }
  }

  test("flushCurrentFile flushes file output to disk") {
    withTmpFile { filename =>
      fileManager.openFile(filename)
      val f = fileManager.currentFile
      fileManager.ensureMode(FileMode.Append)
      fileManager.writeOutputObject(output)
      fileManager.flushCurrentFile()
      val bufferedReader = new BufferedReader(new FileReader(new JFile(f.get.getAbsolutePath)))
      assert(bufferedReader.readLine() == "bar")
    }
  }

  test("setPrefix not error when given the empty string") {
    fileManager.setPrefix("")
    assert(fileManager.prefix == "")
  }

  test("setPrefix accepts an absolute directory") {
    fileManager.setPrefix("/")
    assert(fileManager.prefix == "/")
  }

  test("setPrefix sets the prefix with a trailing slash") {
    fileManager.setPrefix("/tmp")
    assert(fileManager.prefix == "/tmp/")
  }

  test("setPrefix should be the home directory when not set") {
    assert(fileManager.prefix == userHome)
  }

  test("setPrefix canonicalizes the prefix") {
    val f = new java.io.File(userHome + java.io.File.separatorChar + "tmp" + java.io.File.separatorChar + "foobar")
    fileManager.setPrefix("tmp" + java.io.File.separatorChar + "foobar")
    assert(fileManager.prefix == f.getCanonicalPath + java.io.File.separatorChar)
  }

  test("readLine throws an IO Exception if the file is at EOF") {
    withWrittenFile("", file =>
      intercept[IOException](fileManager.readLine())
    )
  }

  test("readLine throws an IOException if the file is opened in the wrong mode") {
    withWrittenFile("abc", { file =>
      fileManager.ensureMode(FileMode.Write)
      intercept[IOException](fileManager.readLine())
    } )
  }

  test("readLine raises a meaningful exception if there is no file opened") {
    intercept[IOException](fileManager.readLine())
  }

  testReadLine("abc", "abc", 3)
  testReadLine("abc\n", "abc", 4)
  testReadLine("abc\r", "abc", 4)
  testReadLine("abc\r\n", "abc", 5)
  testReadLine("abc\na", "abc", 4)
  testReadLine("abc\ra", "abc", 4)
  testReadLine("abc\r\na", "abc", 5)
  testReadLine("\na", "", 1)
  testReadLine("\ra", "", 1)
  testReadLine("\r\na", "", 2)

  test("readChars raises a meaningful exception if there is no file opened") {
    intercept[IOException](fileManager.readChars(1))
  }

  test("readChars raises a meaningful exception if the file is in write mode") {
    withOpenedFile {
      fileManager.ensureMode(FileMode.Write)
      intercept[IOException](fileManager.readChars(1))
    }
  }

  test("readChars raises a meaningful exception if the file is as EOF") {
    withWrittenFile("", file => intercept[IOException](fileManager.readChars(1)))
  }

  test("readChars reads the correct number of chars and advances position appropriately") {
    assertReadCharsAndAdvances("abc", 0, "", 0)
    assertReadCharsAndAdvances("abc", 1, "a", 1)
    assertReadCharsAndAdvances("abc", 3, "abc", 3)
  }

  test("readChars does the right thing when asked to read past end of file") {
    assertReadCharsAndAdvances("abc", 10, "abc", 3)
  }

  test("eof raises an exception if no file is opened") {
    intercept[IOException](fileManager.eof)
  }

  test("eof raises an exception if the file is not in read mode") {
    withOpenedFile {
      fileManager.ensureMode(FileMode.Write)
      intercept[IOException](fileManager.eof)
    }
  }

  test("eof returns true and sets files EOF if the current file is at EOF") {
    withWrittenFile("", { file =>
      assert(fileManager.eof)
      assert(file.eof)
      assert(file.pos == 0)
    })
  }

  test("eof returns false does not set file's eof to true if the reader is not at EOF") {
    withWrittenFile("a", { file =>
      assert(! fileManager.eof)
      assert(! file.eof)
      assert(file.pos == 0)
      assert(file.reader.read() == 'a')
    })
  }

  test("getErrorInfo raises some sort of Exception if no file is opened") {
    intercept[IOException](fileManager.getErrorInfo)
  }

  test("getErrorInfo returns the line number and character number where an error occurred") {
    withWrittenFile("abc\ndef", { file =>
      fileManager.readLine()
      fileManager.readChars(1)
      val errorInfo = fileManager.getErrorInfo
      assertResult(" (line number 2, character 2)")(errorInfo)
      fileManager.openFile(file.getAbsolutePath) // needed by the test, but ugly
    })
  }

  test("read raises an IOException if asked to read when at EOF") {
    withWrittenFile("", {file => intercept[IOException](fileManager.read(dummyWorkspace.world))})
  }

  test("read passes the file contents through to the compiler frontEnd") {
    withWrittenFile("abc", {file =>
      assert(fileManager.read(dummyWorkspace.world) == "abc")
      assert(readFile.isDefined)
      assert(readFile.get == file)
    })
  }

  val dummyCompiler = new DummyCompiler

  val dummyWorkspace = new DummyAbstractWorkspace {
    override def compiler = dummyCompiler
  }

  val fileManager = new DefaultFileManager(dummyWorkspace)

  val openModes = Set(FileMode.Write, FileMode.Append, FileMode.Read)
  val allModes = openModes + FileMode.None

  val output = new OutputObject("", "bar", false, false)

  val userHome = System.getProperty("user.home")

  var readFile: Option[File] = None

  def testReadLine(fileText: String, expectedRead: String, i: Int) = {
    test(s"readLine, given a file with '${unescape(fileText)}', reads '$expectedRead', advancing file position $i") {
      withWrittenFile(fileText, { file =>
        val actualRead = fileManager.readLine()
        assertResult(expectedRead)(actualRead)
        assertCorrectPosition(file, fileText, i)
      })
    }
  }

  def assertCorrectPosition(file: File, fileText: String, expectedPos: Int) = {
    assertResult(expectedPos)(file.pos)
    if (expectedPos < fileText.length) {
      val c = file.reader.read().asInstanceOf[Char]
      assert(unescape(c.toString) == unescape(fileText(expectedPos).toString))
    }
  }

  def assertReadCharsAndAdvances(fileText: String, c: Int, expectedRead: String, i: Int) = {
    withWrittenFile(fileText, { file =>
      val actualRead = fileManager.readChars(c)
      assert(expectedRead == actualRead)
      assertCorrectPosition(file, fileText, i)
    })
  }

  private def unescape(s: String): String = s.flatMap {
    case '\r' => "\\r"
    case '\n' => "\\n"
    case x => x.toString
  }

  def withTmpFile(withFile: String => Unit): Unit = {
    val f = JFile.createTempFile("DefaultFileManagerTmp", "txt")
    withFile(f.getCanonicalPath)
    if (f.exists())
      f.delete()
  }

  def withOpenedFile(withFile: => Unit): Unit = {
    withTmpFile { filename =>
      fileManager.openFile(filename)
      withFile
      fileManager.closeCurrentFile()
    }
  }

  def withWrittenFile(text: String, withFile: File => Unit): Unit = {
    withTmpFile { filename =>
      val w = new PrintWriter(new JFile(filename))
      w.write(text)
      w.close()
      fileManager.openFile(filename)
      withFile(fileManager.currentFile.get)
      fileManager.closeCurrentFile()
    }
  }

  class DummyCompiler extends CompilerInterface {
    override def readFromFile(currFile: File, world: World, extensionManager: APIExtensionManager): AnyRef = {
      readFile = Some(currFile)
      "abc"
    }

    //don't care about the rest
    override def compileProgram(source: String, program: Program,
                                extensionManager: APIExtensionManager, flags: CompilerFlags): CompilerResults = ???
    override def compileMoreCode(source: String, displayName: Option[String],
                                 program: Program, oldProcedures: ProceduresMap,
                                 extensionManager: APIExtensionManager, flags: CompilerFlags): CompilerResults = ???
    override def checkCommandSyntax(source: String,
                                    program: Program,
                                    procedures: ProceduresMap,
                                    extensionManager: APIExtensionManager,
                                    parse: Boolean): Unit = ???
    override def readFromString(source: String, is3D: Boolean): AnyRef = ???
    override def readFromString(source: String, world: World,
                                extensionManager: APIExtensionManager,
                                is3D: Boolean): AnyRef = ???
    override def autoConvert(source: String, subprogram: Boolean,
                             reporter: Boolean, version: String,
                             workspace: AnyRef, ignoreErrors: Boolean, is3D: Boolean): String = ???
    override def findProcedurePositions(source: String, is3D: Boolean): Map[String, (String, Int, Int, Int)] = ???
    override def isReporter(s: String, program: Program,
                            procedures: ProceduresMap, extensionManager: APIExtensionManager): Boolean = ???
    override def checkReporterSyntax(source: String, program: Program,
                                     procedures: ProceduresMap, extensionManager: APIExtensionManager,
                                     parse: Boolean): Unit = ???
    @throws(classOf[IOException])
    override def getTokenAtPosition(source: String, position: Int): Token = ???
    override def tokenizeForColorization(source: String,
                                         extensionManager: APIExtensionManager, is3D: Boolean): Seq[Token] = ???
    override def findIncludes(sourceFileName: String, source: String, is3D: Boolean): Map[String, String] = ???
    override def isValidIdentifier(s: String, is3D: Boolean): Boolean = ???
    override def readNumberFromString(source: String, world: World,
                                      extensionManager: APIExtensionManager, is3D: Boolean): AnyRef = ???
    override def getCompletions(source: String, is3D: Boolean): Seq[(String, String)] = ???
  }
}