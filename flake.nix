{
  description = "Cross-stack ORES comparison development shell";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      eachSystem = f: nixpkgs.lib.genAttrs systems (system: f system);
    in {
      devShells = eachSystem (system:
        let pkgs = import nixpkgs { inherit system; };
        in {
          default = pkgs.mkShell {
            packages = with pkgs; [
              age sops jq yq python3 just git curl gnused coreutils
              gleam erlang rebar3 rustc cargo nodejs_22
              postgresql_16 protobuf docker
            ];
            shellHook = ''
              echo "ores-comparisons: contracts + sops/age + postgres + stack tooling"
              echo "install pinned ores-compose with ./scripts/install-ores-compose.sh"
            '';
          };
        });
    };
}
