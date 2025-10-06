package framing

import (
	"encoding/binary"
	"errors"
	"io"
)

// Frame header (little endian):
// magic[4] = 'D','W','N','B'
// version:1
// flags:1
// streamID:4
// seq:8
// length:4 (payload length)

type Frame struct {
	Version  uint8
	Flags    uint8
	StreamID uint32
	Seq      uint64
	Payload  []byte
}

var magic = [4]byte{'D','W','N','B'}

func Encode(w io.Writer, f *Frame) error {
	if _, err := w.Write(magic[:]); err != nil { return err }
	if err := binary.Write(w, binary.LittleEndian, f.Version); err != nil { return err }
	if err := binary.Write(w, binary.LittleEndian, f.Flags); err != nil { return err }
	if err := binary.Write(w, binary.LittleEndian, f.StreamID); err != nil { return err }
	if err := binary.Write(w, binary.LittleEndian, f.Seq); err != nil { return err }
	length := uint32(len(f.Payload))
	if err := binary.Write(w, binary.LittleEndian, length); err != nil { return err }
	if length > 0 {
		_, err := w.Write(f.Payload)
		return err
	}
	return nil
}

func Decode(r io.Reader) (*Frame, error) {
	head := make([]byte, 4)
	if _, err := io.ReadFull(r, head); err != nil { return nil, err }
	if head[0]!=magic[0] || head[1]!=magic[1] || head[2]!=magic[2] || head[3]!=magic[3] {
		return nil, errors.New("bad magic")
	}
	var f Frame
	if err := binary.Read(r, binary.LittleEndian, &f.Version); err != nil { return nil, err }
	if err := binary.Read(r, binary.LittleEndian, &f.Flags); err != nil { return nil, err }
	if err := binary.Read(r, binary.LittleEndian, &f.StreamID); err != nil { return nil, err }
	if err := binary.Read(r, binary.LittleEndian, &f.Seq); err != nil { return nil, err }
	var length uint32
	if err := binary.Read(r, binary.LittleEndian, &length); err != nil { return nil, err }
	if length > 0 {
		f.Payload = make([]byte, length)
		if _, err := io.ReadFull(r, f.Payload); err != nil { return nil, err }
	}
	return &f, nil
}
