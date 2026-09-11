# Administrator login entrance

The screenshot shows the anonymous login page without a browser initialization action. Source: `be78d0b82e4f422f0e98d0ae30fa297bbc720e97`, clean working tree.

The isolated application was built with `./gradlew stageAcceptanceRuntime` and `docker compose -p poketto-cli-20260911 --env-file .gradle/cli/fixture.env -f acceptance/compose.yaml up --build -d`. The application container was started after runtime staging completed. The fixture uses the real Spring application services, PostgreSQL 17, production Next.js frontend build, and Caddy; only content and account data are synthetic. It does not contact a production content repository.

Chrome, viewport 1920 x 945. After the session check completed, the login form was captured with empty fields. The terminal-created administrator then signed in successfully and reached the real content workspace. Running the initialization command a second time exited with status 1 before any credential prompt. Terminal passwords did not echo.

The administrator was created by invoking `java -cp "/runtime/classes:/runtime/jars/*" io.github.core607.poketto.PokettoApplication admin init` through an interactive Docker terminal against the isolated fixture database after resetting only its seeded account state. The packaged Java launcher is used by the deployment command; this local run uses the staged acceptance classpath.

The screenshot proves removal of the initialization action and preservation of the login layout. It does not claim delivery of the remaining registration and workspace UX plan. PostgreSQL and HTTP tests cover initialization guards, removed HTTP access, and login/session behavior. No production credentials or personal content are included.
