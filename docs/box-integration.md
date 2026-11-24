# Box integration on Paper

Deploy the Box release that removes the direct Kansokusha calls and the new Kansokusha Paper
JAR together in one server restart. Box 6.0.0-rc.8 still calls Kansokusha directly for
AutoStore and Box Stick. Enabling both those direct calls and the event subscribers would
record each operation twice. Kansokusha checks for the new Stick container type snapshot
before subscribing and skips the subscriptions with rc.8. Keep the two JARs paired when
rolling back the migration as well.

Before starting the server, add this policy under the existing `retention.policies` in
`plugins/Kansokusha/config.yml`. Kansokusha leaves existing config files as they are.
Keep the existing `retention.default` and `audit` settings.

```yaml
    box-stock:
      duration: P30D
      archive: true
      event-types:
        - box:stock_change
```

Grant `kansokusha.command.search.event.box:stock_change` to groups that should see the stock
records, alongside their normal command permissions. Use
`/kansokusha search action box:stock_change` to find the records and
`/kansokusha event <id>` to open a record's details. AutoStore and Box Stick continue to
use the existing pickup and container transaction search permissions and retention policy.

## Local verification before the next Box release

The Box API with the container type snapshot is unpublished at the time of this change.
Kansokusha compiles against the published rc.8 APIs and discovers the additional getter
at startup. Build the paired Box checkout to exercise the subscribers on a local Paper
server. From sibling checkouts containing the two changes, run the following commands.

```bash
cd Box
./gradlew :box-api:check :box-core:check :box-autostore-feature:check :box-stick-feature:check :box-bundle:shadowJar
cd ../Kansokusha
./gradlew -PboxCheckout=../Box :kansokusha-paper:check :kansokusha-paper:shadowJar
```

`boxCheckout` uses the locally built Box API and feature JARs on the compile classpath.
The check includes a Paper fixture that starts Kansokusha without Box and inspects the
packaged JAR. To exercise the integration, use the newly built Box and Kansokusha bundles
on a loopback-only Paper test server. Check individual and shared stock, an unchanged
quantity, a partially accepted deposit at the cap, and a full reset. Look for one row
per change, including one reset row per item with its original quantity. Trigger an
AutoStore pickup and a Box Stick transfer on each half of a double chest. Each Stick
operation should record its item deltas at the clicked block under a single transaction ID.
Compare the search rows and event details in English and Japanese, including earlier
pickup and Box Stick rows and ordinary pickup and container rows.
