"""Optional real HTTP contract check using reactor-compiled Java classes.

Set RAG_TOOLS_JAVA_CLASSPATH to the HTTP module's test classpath and JAVA_HOME
to a JDK 17 installation. The endpoint uses the production schema and a local
algorithm substitute; no model, business service, or database is contacted.
"""

import os
from pathlib import Path
import socket
import subprocess
import threading
import time
import unittest
from unittest.mock import patch

import uvicorn

from rag_tools.main import app
from rag_tools.schemas.graph_extract import GraphExtractResponse


class JavaGraphHttpContractTest(unittest.TestCase):
    @unittest.skipUnless(os.environ.get("RAG_TOOLS_JAVA_CLASSPATH"), "Java reactor classpath required")
    def test_java_client_sends_complete_body_to_uvicorn(self):
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
        server = uvicorn.Server(uvicorn.Config(app, http="httptools", log_level="warning"))
        worker = threading.Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)

        def extract(request):
            self.assertEqual(request.operation, "plan")
            self.assertEqual(request.chunks[-1].chunk_id, 2498400033909540227)
            self.assertEqual(request.chunks[-1].text, "Alpha calls Beta. " * 100)
            return GraphExtractResponse(metadata={"receivedChunks": len(request.chunks)})

        with patch("rag_tools.main.extract_graph", side_effect=extract):
            worker.start()
            try:
                deadline = time.monotonic() + 5
                while not server.started:
                    self.assertLess(time.monotonic(), deadline, "Uvicorn startup timeout")
                    time.sleep(0.01)
                java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java")
                fixture = Path(__file__).parent / "fixtures" / "GraphHttpProbe.java"
                result = subprocess.run(
                    [java, "--class-path", os.environ["RAG_TOOLS_JAVA_CLASSPATH"],
                     str(fixture), f"http://127.0.0.1:{port}"],
                    capture_output=True, text=True, timeout=15, check=False,
                )
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("JAVA_UVICORN_OK chunks=228", result.stdout)
            finally:
                server.should_exit = True
                worker.join(5)
                listener.close()
                self.assertFalse(worker.is_alive(), "Uvicorn did not stop")


if __name__ == "__main__":
    unittest.main()
