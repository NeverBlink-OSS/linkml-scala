import os
import glob
from linkml.generators.shaclgen import ShaclGenerator
from linkml.generators.jsonschemagen import JsonSchemaGenerator

skips = [
    # Python doesn't ship with extended types???
    "unionRange",
    "anything",
    "externalType",
    # Python doesn't always URL-encode names
    "syntheticUris",
    # Metamodel difference
    "equalsExpression",
    # Python ignores constraints of union_of members in JSON Schema
    "unionOf",
    # Python's metamodel has no minimum_length and maximum_length
    "lengths",
    "lengthsNarrowing",
    # Python writes a union_of type as a string, and lets a child type, a slot and a child slot
    # widen bounds
    "boundsNarrowing",
]

if __name__ == "__main__":
    for model in glob.glob("tests/resources/models/**/model.yaml", recursive=True):
        result_dir = ".generated/" + os.path.dirname(model)
        name = os.path.basename(result_dir)
        print(f"Generating {name}")

        if name in skips:
            continue
        os.makedirs(result_dir, exist_ok=True)

        shacl = ShaclGenerator(model).serialize()
        with open(result_dir + "/shacl.ttl", "w") as shacl_file:
            shacl_file.truncate()
            shacl_file.write(shacl)

        jsonschema = JsonSchemaGenerator(model).serialize()
        with open(result_dir + "/schema.json", "w") as jsonschema_file:
            jsonschema_file.truncate()
            jsonschema_file.write(jsonschema)

