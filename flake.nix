{
  description = "Heron dev environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [
            pkgs.clojure
            pkgs.jdk21
            pkgs.clj-kondo
            pkgs.awscli2
          ];

          shellHook = ''
            echo "Heron dev shell ready (Clojure $(clojure --version 2>&1 | head -1), Java $(java -version 2>&1 | head -1))"
            # Dummy credentials for LocalStack (real creds come from ~/.aws or env in production)
            export AWS_ACCESS_KEY_ID=''${AWS_ACCESS_KEY_ID:-test}
            export AWS_SECRET_ACCESS_KEY=''${AWS_SECRET_ACCESS_KEY:-test}
            export AWS_DEFAULT_REGION=''${AWS_DEFAULT_REGION:-us-east-1}
          '';
        };
      });
}
