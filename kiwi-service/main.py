from fastapi import FastAPI
from pydantic import BaseModel
from kiwipiepy import Kiwi

app = FastAPI()
kiwi = Kiwi()

class TokenizeRequest(BaseModel):
    text: str

class TokenizeResponse(BaseModel):
    tokens: str

NOUN_TAGS = {'NNG', 'NNP', 'NNB', 'NR', 'NP', 'SL', 'SH', 'SN'}

@app.post("/tokenize", response_model=TokenizeResponse)
def tokenize(req: TokenizeRequest):
    if not req.text.strip():
        return TokenizeResponse(tokens="")
    result = kiwi.tokenize(req.text)
    tokens = " ".join(
        t.form for t in result
        if t.form.strip() and str(t.tag) in NOUN_TAGS
    )
    return TokenizeResponse(tokens=tokens)

@app.get("/health")
def health():
    return {"status": "ok"}
