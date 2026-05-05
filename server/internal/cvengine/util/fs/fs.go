package fs

import (
	"errors"
	"io"
	"io/ioutil"
	"os"
	"path"
	"time"
)

type Dir struct {
	Name           string
	LastModifiedAt time.Time
}

type File struct {
	Name           string
	Size           int64
	LastModifiedAt time.Time
}

func NewDir(name string, modificationAt time.Time) *Dir {
	return &Dir{
		Name:           name,
		LastModifiedAt: modificationAt}
}

func NewFile(name string, size int64, modificationAt time.Time) *File {
	return &File{
		Name:           name,
		Size:           size,
		LastModifiedAt: modificationAt}
}

func GetSubDirs(rootPath string) ([]*Dir, error) {
	if !isDirectory(rootPath) {
		return nil, errors.New("Root path is not a directory")
	}
	files, _ := ioutil.ReadDir(rootPath)

	dirs := make([]*Dir, len(files))

	index := 0
	for _, f := range files {
		dirs[index] = NewDir(f.Name(), f.ModTime())
		index++
	}
	return dirs, nil
}

func HasDir(filePath string) bool {
	stat, err := os.Stat(filePath)
	if err != nil {
		return false
	}

	mode := stat.Mode()
	return mode.IsDir()
}

func GetDir(rootPath string) (*Dir, error) {
	if !isDirectory(rootPath) {
		return nil, errors.New("Root path is not a directory")
	}

	f, err := os.Open(rootPath)
	if err != nil {
		return nil, err
	}

	stat, err := f.Stat()
	if err != nil {
		return nil, err
	}
	return NewDir(stat.Name(), stat.ModTime()), nil
}

func AddDir(rootPath string) error {
	err := os.MkdirAll(rootPath, 0711)
	if err != nil {
		return err
	}
	return nil
}

func AddDirs(dirs []string) error {
	for _, dir := range dirs {
		if dir == "" {
			continue
		}

		err := AddDir(dir)
		if err != nil {
			return err
		}
	}

	return nil
}

func DelDir(rootPath string) error {
	err := os.RemoveAll(rootPath)
	if err != nil {
		return err
	}
	return nil
}

func DelDirs(dirs []string) error {
	for _, dir := range dirs {
		err := DelDir(dir)
		if err != nil {
			return err
		}
	}

	return nil
}

func RenameDir(from, to string) error {
	err := os.Rename(from, to)
	if err != nil {
		return err
	}
	return nil
}

func GetFiles(rootPath string, recursive bool) ([]*File, error) {
	files, err := ioutil.ReadDir(rootPath)
	if err != nil {
		return nil, err
	}

	var fileArr []*File
	if recursive {
		for _, f := range files {
			if f.IsDir() {
				arr, err := GetFiles(path.Join(rootPath, f.Name()), recursive)
				if err != nil {
					return nil, err
				}
				for _, a := range arr {
					fileArr = append(fileArr, a)
				}
			} else {
				file := NewFile(path.Join(rootPath, f.Name()), f.Size(), f.ModTime())
				fileArr = append(fileArr, file)
			}
		}
	} else {
		for _, f := range files {
			if f.IsDir() {
				continue
			}

			file := NewFile(path.Join(rootPath, f.Name()), f.Size(), f.ModTime())
			fileArr = append(fileArr, file)
		}
	}

	return fileArr, nil
}

func HasFile(filePath string) bool {
	stat, err := os.Stat(filePath)
	if err != nil {
		return false
	}

	mode := stat.Mode()
	return mode.IsRegular()
}

func GetFile(filePath string) (*File, error) {
	f, err := os.Open(filePath)
	if err != nil {
		return nil, err
	}

	stat, err := f.Stat()
	if err != nil {
		return nil, err
	}
	return NewFile(stat.Name(), stat.Size(), stat.ModTime()), nil
}

func CopyFile(srcPath string, dstPath string) error {
	src, err := os.Open(srcPath)
	if err != nil {
		return err
	}
	defer src.Close()

	dst, err := os.OpenFile(dstPath, os.O_WRONLY|os.O_CREATE, 0644)
	if err != nil {
		return err
	}
	defer dst.Close()

	_, err = io.Copy(dst, src)
	return err
}

func DelFile(filePath string) error {
	err := os.Remove(filePath)
	if err != nil {
		return err
	}
	return nil
}

func DelFiles(files []string) error {
	for _, filePath := range files {
		err := DelFile(filePath)
		if err != nil {
			return err
		}
	}

	return nil
}

func isDirectory(filePath string) bool {
	stat, err := os.Stat(filePath)
	if err != nil {
		return false
	}
	switch mode := stat.Mode(); {
	case mode.IsDir():
		return true
	case mode.IsRegular():
		return false
	}
	return false
}

func GetDirSize(dirPath string, dirSize int64) int64 {
	flist, e := ioutil.ReadDir(dirPath)
	if e != nil {
		return 0
	}
	for _, f := range flist {
		if f.IsDir() {
			dirSize += GetDirSize(dirPath+"/"+f.Name(), dirSize)
		} else {
			dirSize += f.Size()
		}
	}
	return dirSize
}
