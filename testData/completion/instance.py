from builtins import *
from pydantic import BaseModel


class A(BaseModel):
    abc: str
    cde: str = str('abc')
    efg: str = str('abc')

class B(A):
    hij: str

A().<caret>