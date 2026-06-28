package initkit.cli

import utest.*
import upickle.default.{read, write}

object AppSnapshotTests extends TestSuite:
  val tests: Tests = Tests:
    test("collects visible files"):
      val tmp = os.temp.dir()
      try
        os.write(tmp / "one.txt", "one")
        os.makeDir(tmp / ".git")
        os.write(tmp / ".git" / "ignored.txt", "ignored")

        val snapshot = AppSnapshot.collect("demo", tmp)

        assert(snapshot.name == "demo")
        assert(snapshot.cwd == tmp.toString)
        assert(snapshot.files == 1)
      finally
        os.remove.all(tmp)

    test("serializes to json"):
      val snapshot = AppSnapshot("demo", "/tmp/demo", 2)
      val json = write(snapshot)

      assert(read[AppSnapshot](json) == snapshot)
