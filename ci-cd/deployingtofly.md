# Deployment of irv with a separate tunnel connector

This configuration targets the existing Fly app `iruvu`. It is a private boot/UI test, not the completed Auth0/Cloudflare deployment or a working sandbox-provisioning configuration.

## Resources created on deployment

`fly.toml` selects `iad`, one shared CPU, 1024 MB RAM, and a 1 GB volume named `iruvu_data` mounted at `/data`. `fly deploy --ha=false` creates the initial Machine and volume when absent. No separate Machine creation is necessary. Change `primary_region` before the first deployment if another region is preferred. An existing volume does not move regions when this value changes.

The volume holds the app's embedded H2 database, local blob files, and private-test bootstrap credentials. This is the control-plane volume, not a sandbox volume. No PostgreSQL `DATABASE_URL`, Tigris credentials, runtime Fly API token, sandbox images, or custom domain is required for this test. Database metadata is still necessary; the first-deploy profile supplies embedded H2 rather than removing persistence.

No Fly HTTP service is public. The separate `iruvu-tunnel` app can reach the isolated status listener through `http://iruvu.internal:8081`. Its default page says `connected successfully`; other paths return 404 and methods other than GET/HEAD return 405 at `/`. Application routes on port 8080 remain private. The Machine stays running until stopped manually; volume storage remains billable after stopping. Tunnel traffic does not automatically restart a stopped Machine.

## Deploy

1. Merge the deployment changes into `main`.
2. Create an app-scoped deploy token for `iruvu` using Fly's CLI/account and store it as the repository Actions secret `FLY_API_TOKEN`. Do not commit or print it. This token is for deployment, not an app runtime secret.
3. In GitHub Actions, select **Fly deployment**, choose branch `main`, select action **deploy**, and run it. A push of a `deploy/*` tag whose commit belongs to main also deploys. Ordinary pushes to main do not deploy.
4. Alternatively, with Fly CLI installed and `FLY_API_TOKEN` securely exported, run `bash ci-cd/deploy.sh deploy` from the checkout.
5. From a computer authenticated to the Fly app, run `flyctl proxy 8080:8080 -a iruvu`. Open `http://localhost:8080`. Health is available at `http://localhost:8080/api/health`.

Follow [tunnel/README.md](tunnel/README.md) to create and deploy the separate connector, move `TUNNEL_TOKEN` off this Java app, and route your domain to the status listener. Do not allocate public Fly IPs. The direct iruvu.fly.dev route is removed. The agreed public authentication model is not implemented by this connectivity step. `IRV_SECURE_COOKIES=false` remains only for the private application accessed through the local HTTP proxy; the status page sets no cookies.

## Initial account

The container entrypoint creates a random encryption key and bootstrap invite on the volume if they are absent, only for the explicit `first-deploy` profile. Neither value is printed to startup logs. Retrieve the invite privately using `flyctl ssh console -a iruvu -C 'cat /data/private/bootstrap-invite'`, then use the existing registration screen. Treat the output as a secret. Existing application behavior gives the bootstrap admin invitation a 24-hour lifetime; simply restarting does not renew an expired database invitation. Existing password/TOTP authentication remains unchanged.

The generated encryption key persists across restarts at `/data/private/encryption.key`. Keep the volume intact. This convenience stores the key alongside test data; it does not provide production key separation. Outside this profile, the existing requirement to supply `IRV_ENCRYPTION_KEY` remains.

## Stop and inspect

- Select **stop** in the workflow, or run `bash ci-cd/deploy.sh stop`. This preserves the volume.
- Inspect startup errors with `flyctl logs -a iruvu` and resource state with `flyctl status -a iruvu`.
- Stop does not snapshot sandbox data to Tigris. It only stops this app's Machines.
- UI boot, health, and existing account flows can be tested. Creating actual sandboxes still requires the sandbox images and Fly provisioning configuration; this change does not simulate their success.

## Relationship to Telugu-now

`ci-cd/deploy.sh` is copied from Telugu-now with only its repository target changed to `ssr2zvy/irv`. The deployment workflow is copied with one functional correction: it passes the selected action to the script instead of always invoking `deploy`, which would deploy even when **stop** was selected. No automatic dependency-upgrade workflow is introduced.

`ci-cd/Containerfile` builds irv's frontend and Java backend. The root `Dockerfile` is identical to keep the existing local build path working. The entrypoint prepares volume ownership and then runs Java as the unprivileged `irv` user.

## Verification limits

This configuration must still be built and exercised on Fly. Local verification is not proof of a successful remote deployment. External dependency downloads and a container runtime are required for a fresh full image build.
