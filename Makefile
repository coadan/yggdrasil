.PHONY: build check test test-jvm-dotnet benchmark-check benchmark-quick benchmark-dogfood-check benchmark-dogfood benchmark-dogfood-plugins benchmark-dogfood-manifest benchmark-python benchmark-jvm-dotnet benchmark-terraform release clean

build:
	mkdir -p bin
	go build -buildvcs=false -trimpath -o bin/ygg ./cmd/ygg
	go build -buildvcs=false -trimpath -o bin/yggbench ./cmd/yggbench
	cd plugins/markdown && go build -buildvcs=false -trimpath -o ../../bin/ygg-extract-markdown .
	cd plugins/go && go build -buildvcs=false -trimpath -o ../../bin/ygg-extract-go .
	cd plugins/typescript && go build -buildvcs=false -trimpath -o ../../bin/ygg-extract-typescript .
	cd plugins/manifest && go build -buildvcs=false -trimpath -o ../../bin/ygg-extract-manifest .
	cd plugins/terraform && go build -buildvcs=false -trimpath -o ../../bin/ygg-extract-terraform .

test:
	go test ./...
	cd plugins/markdown && go test ./...
	cd plugins/go && go test ./...
	cd plugins/typescript && go test ./...
	cd plugins/manifest && go test ./...
	cd plugins/terraform && go test ./...
	PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s plugins/python -p 'test_*.py'
	PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s plugins/jvm-dotnet -p 'test_*.py'

test-jvm-dotnet:
	PYTHONDONTWRITEBYTECODE=1 .dev/jvm-dotnet-venv/bin/python \
		-m unittest discover -s plugins/jvm-dotnet -p 'test_*.py'

check:
	test -z "$$(gofmt -l $$(git ls-files '*.go'))"
	go vet ./...
	cd plugins/markdown && go vet ./...
	cd plugins/go && go vet ./...
	cd plugins/typescript && go vet ./...
	cd plugins/manifest && go vet ./...
	cd plugins/terraform && go vet ./...
	$(MAKE) test

benchmark-quick: build
	bin/yggbench -prepare -suite benchmarks/claim-quick.json -ygg bin/ygg

benchmark-check: build
	bin/yggbench -prepare -check-only -suite benchmarks/claim-quick.json -ygg bin/ygg

benchmark-dogfood: build
	bin/yggbench -prepare -suite benchmarks/dogfood-replay.json -ygg bin/ygg -out .dev/bench/dogfood-report.json

benchmark-dogfood-check: build
	bin/yggbench -prepare -check-only -suite benchmarks/dogfood-replay.json -ygg bin/ygg

benchmark-dogfood-plugins: build
	bin/yggbench -prepare -suite benchmarks/dogfood-replay.json \
		-plugin-config benchmarks/dogfood-plugins.json \
		-work .dev/bench/work-dogfood-plugins \
		-ygg bin/ygg -out .dev/bench/dogfood-plugin-report.json

benchmark-dogfood-manifest: build
	bin/yggbench -prepare -suite benchmarks/dogfood-replay.json \
		-plugin-config benchmarks/dogfood-manifest.json \
		-work .dev/bench/work-dogfood-manifest \
		-ygg bin/ygg -out .dev/bench/dogfood-manifest-report.json

benchmark-python: build
	bin/yggbench -prepare -suite benchmarks/claim-quick.json \
		-cases flask-autoescape-case-insensitive,graphify-read-glob-hook-extension-boundary \
		-work .dev/bench/work-python-default \
		-ygg bin/ygg -out .dev/bench/python-default-report.json
	bin/yggbench -prepare -suite benchmarks/claim-quick.json \
		-cases flask-autoescape-case-insensitive,graphify-read-glob-hook-extension-boundary \
		-plugin-config benchmarks/python-plugins.json \
		-work .dev/bench/work-python-plugin \
		-ygg bin/ygg -out .dev/bench/python-plugin-report.json

benchmark-jvm-dotnet: build
	bin/yggbench -prepare -suite benchmarks/claim-quick.json \
		-cases dapper-prefer-enum-type-handlers \
		-work .dev/bench/work-jvm-dotnet-default \
		-ygg bin/ygg -out .dev/bench/jvm-dotnet-default-report.json
	PATH="$(CURDIR)/.dev/jvm-dotnet-venv/bin:$$PATH" \
		bin/yggbench -prepare -suite benchmarks/claim-quick.json \
		-cases dapper-prefer-enum-type-handlers \
		-plugin-config benchmarks/jvm-dotnet-plugins.json \
		-work .dev/bench/work-jvm-dotnet-plugin \
		-ygg bin/ygg -out .dev/bench/jvm-dotnet-plugin-report.json

benchmark-terraform: build
	bin/yggbench -prepare -suite benchmarks/claim-quick.json \
		-cases terraform-vpc-endpoint-dns-record-ip-type \
		-work .dev/bench/work-terraform-default \
		-ygg bin/ygg -out .dev/bench/terraform-default-report.json
	bin/yggbench -prepare -suite benchmarks/claim-quick.json \
		-cases terraform-vpc-endpoint-dns-record-ip-type \
		-plugin-config benchmarks/terraform-plugins.json \
		-work .dev/bench/work-terraform-plugin \
		-ygg bin/ygg -out .dev/bench/terraform-plugin-report.json

release:
	mkdir -p dist
	CGO_ENABLED=0 GOOS=darwin GOARCH=arm64 go build -buildvcs=false -trimpath -ldflags="-s -w" -o dist/ygg-darwin-arm64 ./cmd/ygg
	CGO_ENABLED=0 GOOS=darwin GOARCH=amd64 go build -buildvcs=false -trimpath -ldflags="-s -w" -o dist/ygg-darwin-amd64 ./cmd/ygg
	CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -buildvcs=false -trimpath -ldflags="-s -w" -o dist/ygg-linux-arm64 ./cmd/ygg
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -buildvcs=false -trimpath -ldflags="-s -w" -o dist/ygg-linux-amd64 ./cmd/ygg
	cd dist && shasum -a 256 ygg-* > SHA256SUMS

clean:
	rm -f bin/ygg bin/yggbench bin/ygg-extract-markdown
	rm -f bin/ygg-extract-go bin/ygg-extract-typescript
	rm -f bin/ygg-extract-manifest
	rm -f bin/ygg-extract-terraform
	rm -f dist/ygg-darwin-arm64 dist/ygg-darwin-amd64
	rm -f dist/ygg-linux-arm64 dist/ygg-linux-amd64
	rm -f dist/SHA256SUMS
