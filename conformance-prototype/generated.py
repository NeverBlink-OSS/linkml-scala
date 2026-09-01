# Auto generated from conformance-ontology.yaml by pythongen.py version: 0.0.1
# Generation date: 2026-09-01T08:06:07
# Schema: conformance
#
# id: https://linkml.neverblink.eu/model/conformance#
# description:
# license: https://creativecommons.org/publicdomain/zero/1.0/

import dataclasses
import re
from dataclasses import dataclass
from datetime import (
    date,
    datetime,
    time
)
from typing import (
    Any,
    ClassVar,
    Dict,
    List,
    Optional,
    Union
)

from jsonasobj2 import (
    JsonObj,
    as_dict
)
from linkml_runtime.linkml_model.meta import (
    EnumDefinition,
    PermissibleValue,
    PvFormulaOptions
)
from linkml_runtime.utils.curienamespace import CurieNamespace
from linkml_runtime.utils.enumerations import EnumDefinitionImpl
from linkml_runtime.utils.formatutils import (
    camelcase,
    sfx,
    underscore
)
from linkml_runtime.utils.metamodelcore import (
    bnode,
    empty_dict,
    empty_list
)
from linkml_runtime.utils.slot import Slot
from linkml_runtime.utils.yamlutils import (
    YAMLRoot,
    extended_float,
    extended_int,
    extended_str
)
from rdflib import (
    Namespace,
    URIRef
)

from linkml_runtime.linkml_model.types import String

metamodel_version = "1.11.0"
version = None

# Namespaces
CONFORMANCE = CurieNamespace('conformance', 'https://linkml.neverblink.eu/model/conformance#')
LINKML = CurieNamespace('linkml', 'https://w3id.org/linkml/')
DEFAULT_ = CONFORMANCE


# Types

# Class references



Any = Any

@dataclass(repr=False)
class Manifest(YAMLRoot):
    _inherited_slots: ClassVar[list[str]] = []

    class_class_uri: ClassVar[URIRef] = CONFORMANCE["Manifest"]
    class_class_curie: ClassVar[str] = "conformance:Manifest"
    class_name: ClassVar[str] = "Manifest"
    class_model_uri: ClassVar[URIRef] = CONFORMANCE.Manifest

    name: str = None
    schema: str = None
    title: Optional[str] = None
    description: Optional[str] = None
    entries: Optional[Union[Union[dict, "Test"], list[Union[dict, "Test"]]]] = empty_list()

    def __post_init__(self, *_: str, **kwargs: Any):
        if self._is_empty(self.name):
            self.MissingRequiredField("name")
        if not isinstance(self.name, str):
            self.name = str(self.name)

        if self._is_empty(self.schema):
            self.MissingRequiredField("schema")
        if not isinstance(self.schema, str):
            self.schema = str(self.schema)

        if self.title is not None and not isinstance(self.title, str):
            self.title = str(self.title)

        if self.description is not None and not isinstance(self.description, str):
            self.description = str(self.description)

        if not isinstance(self.entries, list):
            self.entries = [self.entries] if self.entries is not None else []
        self.entries = [v if isinstance(v, Test) else Test(**as_dict(v)) for v in self.entries]

        super().__post_init__(**kwargs)


@dataclass(repr=False)
class Test(YAMLRoot):
    _inherited_slots: ClassVar[list[str]] = []

    class_class_uri: ClassVar[URIRef] = CONFORMANCE["Test"]
    class_class_curie: ClassVar[str] = "conformance:Test"
    class_name: ClassVar[str] = "Test"
    class_model_uri: ClassVar[URIRef] = CONFORMANCE.Test

    action: Union[dict, "Action"] = None
    assertion: Union[dict, "Assertion"] = None
    title: Optional[str] = None
    description: Optional[str] = None

    def __post_init__(self, *_: str, **kwargs: Any):
        if self._is_empty(self.action):
            self.MissingRequiredField("action")
        if not isinstance(self.action, Action):
            self.action = Action(**as_dict(self.action))

        if self._is_empty(self.assertion):
            self.MissingRequiredField("assertion")
        if not isinstance(self.assertion, Assertion):
            self.assertion = Assertion(**as_dict(self.assertion))

        if self.title is not None and not isinstance(self.title, str):
            self.title = str(self.title)

        if self.description is not None and not isinstance(self.description, str):
            self.description = str(self.description)

        super().__post_init__(**kwargs)


@dataclass(repr=False)
class Action(YAMLRoot):
    _inherited_slots: ClassVar[list[str]] = []

    class_class_uri: ClassVar[URIRef] = CONFORMANCE["Action"]
    class_class_curie: ClassVar[str] = "conformance:Action"
    class_name: ClassVar[str] = "Action"
    class_model_uri: ClassVar[URIRef] = CONFORMANCE.Action

    type: Optional[str] = None
    title: Optional[str] = None
    description: Optional[str] = None

    def __post_init__(self, *_: str, **kwargs: Any):
        self.type = str(self.class_name)

        if self.title is not None and not isinstance(self.title, str):
            self.title = str(self.title)

        if self.description is not None and not isinstance(self.description, str):
            self.description = str(self.description)

        super().__post_init__(**kwargs)


    def __new__(cls, *args, **kwargs):

        type_designator = "type"
        if not type_designator in kwargs:
            return super().__new__(cls,*args,**kwargs)
        else:
            type_designator_value = kwargs[type_designator]
            target_cls = cls._class_for("class_name", type_designator_value)


            if target_cls is None:
                raise ValueError(f"Wrong type designator value: class {cls.__name__} "
                                 f"has no subclass with ['class_name']='{kwargs[type_designator]}'")
            return super().__new__(target_cls,*args,**kwargs)



@dataclass(repr=False)
class Assertion(YAMLRoot):
    _inherited_slots: ClassVar[list[str]] = []

    class_class_uri: ClassVar[URIRef] = CONFORMANCE["Assertion"]
    class_class_curie: ClassVar[str] = "conformance:Assertion"
    class_name: ClassVar[str] = "Assertion"
    class_model_uri: ClassVar[URIRef] = CONFORMANCE.Assertion

    type: Optional[str] = None
    title: Optional[str] = None
    description: Optional[str] = None

    def __post_init__(self, *_: str, **kwargs: Any):
        self.type = str(self.class_name)

        if self.title is not None and not isinstance(self.title, str):
            self.title = str(self.title)

        if self.description is not None and not isinstance(self.description, str):
            self.description = str(self.description)

        super().__post_init__(**kwargs)


    def __new__(cls, *args, **kwargs):

        type_designator = "type"
        if not type_designator in kwargs:
            return super().__new__(cls,*args,**kwargs)
        else:
            type_designator_value = kwargs[type_designator]
            target_cls = cls._class_for("class_name", type_designator_value)


            if target_cls is None:
                raise ValueError(f"Wrong type designator value: class {cls.__name__} "
                                 f"has no subclass with ['class_name']='{kwargs[type_designator]}'")
            return super().__new__(target_cls,*args,**kwargs)



@dataclass(repr=False)
class ExpectedFailure(YAMLRoot):
    _inherited_slots: ClassVar[list[str]] = []

    class_class_uri: ClassVar[URIRef] = CONFORMANCE["ExpectedFailure"]
    class_class_curie: ClassVar[str] = "conformance:ExpectedFailure"
    class_name: ClassVar[str] = "ExpectedFailure"
    class_model_uri: ClassVar[URIRef] = CONFORMANCE.ExpectedFailure

    message_assertion: Optional[Union[dict, "StringAssertion"]] = None

    def __post_init__(self, *_: str, **kwargs: Any):
        if self.message_assertion is not None and not isinstance(self.message_assertion, StringAssertion):
            self.message_assertion = StringAssertion(**as_dict(self.message_assertion))

        super().__post_init__(**kwargs)


class LoadAction(Action):
    _inherited_slots: ClassVar[list[str]] = []

    class_class_uri: ClassVar[URIRef] = CONFORMANCE["LoadAction"]
    class_class_curie: ClassVar[str] = "conformance:LoadAction"
    class_name: ClassVar[str] = "LoadAction"
    class_model_uri: ClassVar[URIRef] = CONFORMANCE.LoadAction


    def __post_init__(self, *_: str, **kwargs: Any):

        super().__post_init__(**kwargs)
        self.type = str(self.class_name)


class DeriveAction(Action):
    _inherited_slots: ClassVar[list[str]] = []

    class_class_uri: ClassVar[URIRef] = CONFORMANCE["DeriveAction"]
    class_class_curie: ClassVar[str] = "conformance:DeriveAction"
    class_name: ClassVar[str] = "DeriveAction"
    class_model_uri: ClassVar[URIRef] = CONFORMANCE.DeriveAction


    def __post_init__(self, *_: str, **kwargs: Any):

        super().__post_init__(**kwargs)
        self.type = str(self.class_name)


class LintAction(Action):
    _inherited_slots: ClassVar[list[str]] = []

    class_class_uri: ClassVar[URIRef] = CONFORMANCE["LintAction"]
    class_class_curie: ClassVar[str] = "conformance:LintAction"
    class_name: ClassVar[str] = "LintAction"
    class_model_uri: ClassVar[URIRef] = CONFORMANCE.LintAction


    def __post_init__(self, *_: str, **kwargs: Any):

        super().__post_init__(**kwargs)
        self.type = str(self.class_name)


class GenerateAction(Action):
    _inherited_slots: ClassVar[list[str]] = []

    class_class_uri: ClassVar[URIRef] = CONFORMANCE["GenerateAction"]
    class_class_curie: ClassVar[str] = "conformance:GenerateAction"
    class_name: ClassVar[str] = "GenerateAction"
    class_model_uri: ClassVar[URIRef] = CONFORMANCE.GenerateAction


    def __post_init__(self, *_: str, **kwargs: Any):

        super().__post_init__(**kwargs)
        self.type = str(self.class_name)


class JsonSchemaGenerate(GenerateAction):
    _inherited_slots: ClassVar[list[str]] = []

    class_class_uri: ClassVar[URIRef] = CONFORMANCE["JsonSchemaGenerate"]
    class_class_curie: ClassVar[str] = "conformance:JsonSchemaGenerate"
    class_name: ClassVar[str] = "JsonSchemaGenerate"
    class_model_uri: ClassVar[URIRef] = CONFORMANCE.JsonSchemaGenerate


    def __post_init__(self, *_: str, **kwargs: Any):

        super().__post_init__(**kwargs)
        self.type = str(self.class_name)


class LoadsAssertion(Assertion):
    _inherited_slots: ClassVar[list[str]] = []

    class_class_uri: ClassVar[URIRef] = CONFORMANCE["LoadsAssertion"]
    class_class_curie: ClassVar[str] = "conformance:LoadsAssertion"
    class_name: ClassVar[str] = "LoadsAssertion"
    class_model_uri: ClassVar[URIRef] = CONFORMANCE.LoadsAssertion


    def __post_init__(self, *_: str, **kwargs: Any):

        super().__post_init__(**kwargs)
        self.type = str(self.class_name)


@dataclass(repr=False)
class StringAssertion(Assertion):
    _inherited_slots: ClassVar[list[str]] = []

    class_class_uri: ClassVar[URIRef] = CONFORMANCE["StringAssertion"]
    class_class_curie: ClassVar[str] = "conformance:StringAssertion"
    class_name: ClassVar[str] = "StringAssertion"
    class_model_uri: ClassVar[URIRef] = CONFORMANCE.StringAssertion

    includes: Optional[Union[str, list[str]]] = empty_list()

    def __post_init__(self, *_: str, **kwargs: Any):
        if not isinstance(self.includes, list):
            self.includes = [self.includes] if self.includes is not None else []
        self.includes = [v if isinstance(v, str) else str(v) for v in self.includes]

        super().__post_init__(**kwargs)
        self.type = str(self.class_name)


@dataclass(repr=False)
class JsonPathAssertion(Assertion):
    _inherited_slots: ClassVar[list[str]] = []

    class_class_uri: ClassVar[URIRef] = CONFORMANCE["JsonPathAssertion"]
    class_class_curie: ClassVar[str] = "conformance:JsonPathAssertion"
    class_name: ClassVar[str] = "JsonPathAssertion"
    class_model_uri: ClassVar[URIRef] = CONFORMANCE.JsonPathAssertion

    path: str = None
    value: Union[dict, Any] = None

    def __post_init__(self, *_: str, **kwargs: Any):
        if self._is_empty(self.path):
            self.MissingRequiredField("path")
        if not isinstance(self.path, str):
            self.path = str(self.path)

        super().__post_init__(**kwargs)
        self.type = str(self.class_name)


@dataclass(repr=False)
class JsonSchemaAccepts(Assertion):
    _inherited_slots: ClassVar[list[str]] = []

    class_class_uri: ClassVar[URIRef] = CONFORMANCE["JsonSchemaAccepts"]
    class_class_curie: ClassVar[str] = "conformance:JsonSchemaAccepts"
    class_name: ClassVar[str] = "JsonSchemaAccepts"
    class_model_uri: ClassVar[URIRef] = CONFORMANCE.JsonSchemaAccepts

    instance: str = None

    def __post_init__(self, *_: str, **kwargs: Any):
        if self._is_empty(self.instance):
            self.MissingRequiredField("instance")
        if not isinstance(self.instance, str):
            self.instance = str(self.instance)

        super().__post_init__(**kwargs)
        self.type = str(self.class_name)


@dataclass(repr=False)
class JsonSchemaRejects(Assertion):
    _inherited_slots: ClassVar[list[str]] = []

    class_class_uri: ClassVar[URIRef] = CONFORMANCE["JsonSchemaRejects"]
    class_class_curie: ClassVar[str] = "conformance:JsonSchemaRejects"
    class_name: ClassVar[str] = "JsonSchemaRejects"
    class_model_uri: ClassVar[URIRef] = CONFORMANCE.JsonSchemaRejects

    instance: str = None

    def __post_init__(self, *_: str, **kwargs: Any):
        if self._is_empty(self.instance):
            self.MissingRequiredField("instance")
        if not isinstance(self.instance, str):
            self.instance = str(self.instance)

        super().__post_init__(**kwargs)
        self.type = str(self.class_name)


# Enumerations


# Slots
class slots:
    pass

slots.type = Slot(uri=CONFORMANCE.type, name="type", curie=CONFORMANCE.curie('type'),
                   model_uri=CONFORMANCE.type, domain=None, range=Optional[str])

slots.title = Slot(uri=CONFORMANCE.title, name="title", curie=CONFORMANCE.curie('title'),
                   model_uri=CONFORMANCE.title, domain=None, range=Optional[str])

slots.description = Slot(uri=CONFORMANCE.description, name="description", curie=CONFORMANCE.curie('description'),
                   model_uri=CONFORMANCE.description, domain=None, range=Optional[str])

slots.manifest__name = Slot(uri=CONFORMANCE.name, name="manifest__name", curie=CONFORMANCE.curie('name'),
                   model_uri=CONFORMANCE.manifest__name, domain=None, range=str)

slots.manifest__schema = Slot(uri=CONFORMANCE.schema, name="manifest__schema", curie=CONFORMANCE.curie('schema'),
                   model_uri=CONFORMANCE.manifest__schema, domain=None, range=str)

slots.manifest__entries = Slot(uri=CONFORMANCE.entries, name="manifest__entries", curie=CONFORMANCE.curie('entries'),
                   model_uri=CONFORMANCE.manifest__entries, domain=None, range=Optional[Union[Union[dict, Test], list[Union[dict, Test]]]])

slots.test__action = Slot(uri=CONFORMANCE.action, name="test__action", curie=CONFORMANCE.curie('action'),
                   model_uri=CONFORMANCE.test__action, domain=None, range=Union[dict, Action])

slots.test__assertion = Slot(uri=CONFORMANCE.assertion, name="test__assertion", curie=CONFORMANCE.curie('assertion'),
                   model_uri=CONFORMANCE.test__assertion, domain=None, range=Union[dict, Assertion])

slots.expectedFailure__message_assertion = Slot(uri=CONFORMANCE.message_assertion, name="expectedFailure__message_assertion", curie=CONFORMANCE.curie('message_assertion'),
                   model_uri=CONFORMANCE.expectedFailure__message_assertion, domain=None, range=Optional[Union[dict, StringAssertion]])

slots.stringAssertion__includes = Slot(uri=CONFORMANCE.includes, name="stringAssertion__includes", curie=CONFORMANCE.curie('includes'),
                   model_uri=CONFORMANCE.stringAssertion__includes, domain=None, range=Optional[Union[str, list[str]]])

slots.jsonPathAssertion__path = Slot(uri=CONFORMANCE.path, name="jsonPathAssertion__path", curie=CONFORMANCE.curie('path'),
                   model_uri=CONFORMANCE.jsonPathAssertion__path, domain=None, range=str)

slots.jsonPathAssertion__value = Slot(uri=CONFORMANCE.value, name="jsonPathAssertion__value", curie=CONFORMANCE.curie('value'),
                   model_uri=CONFORMANCE.jsonPathAssertion__value, domain=None, range=Union[dict, Any])

slots.jsonSchemaAccepts__instance = Slot(uri=CONFORMANCE.instance, name="jsonSchemaAccepts__instance", curie=CONFORMANCE.curie('instance'),
                   model_uri=CONFORMANCE.jsonSchemaAccepts__instance, domain=None, range=str)

slots.jsonSchemaRejects__instance = Slot(uri=CONFORMANCE.instance, name="jsonSchemaRejects__instance", curie=CONFORMANCE.curie('instance'),
                   model_uri=CONFORMANCE.jsonSchemaRejects__instance, domain=None, range=str)

