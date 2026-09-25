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
              age
              sops
              jq
              python3
              just
              git
              curl
              gleam
              erlang
              rebar3
              rustc
              cargo
              nodejs_22
            ];
          };
        });
    };
}
